package com.cs3219.foc.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cs3219.foc.order.infrastructure.client.CreditClient;
import com.cs3219.foc.order.infrastructure.client.SupplierClient;
import com.cs3219.foc.order.infrastructure.client.UserClient;
import com.cs3219.foc.order.model.dto.CreditReservationResponse;
import com.cs3219.foc.order.model.dto.SupplierPickupDto;
import com.cs3219.foc.order.model.dto.UserBriefDto;
import com.cs3219.foc.order.model.dto.event.CreditSettlementResultEvent;
import com.cs3219.foc.order.service.OrderService;
import io.awspring.cloud.sns.core.SnsOperations;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.cloud.aws.sqs.enabled=false")
@AutoConfigureMockMvc
class OrderCreationHttpTests extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderService service;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @MockitoBean
    private CreditClient credit;

    @MockitoBean
    private SupplierClient suppliers;

    @MockitoBean
    private UserClient users;

    @MockitoBean
    private SnsOperations sns;

    @MockitoBean
    private JwtDecoder decoder;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE errands, event_publication");
        when(suppliers.get(any()))
                .thenAnswer(call -> new SupplierPickupDto(call.getArgument(0), "Cafe", "COM1", "01", "Lobby", true));
        when(users.find(any())).thenAnswer(call -> ((Set<UUID>) call.getArgument(0))
                .stream().collect(Collectors.toMap(id -> id, id -> new UserBriefDto(id, "User"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "COMPLETED", "CANCELLED", "EXPIRED"})
    void lateHttpCreationPreservesTheStateReachedAfterRetry(String targetStatus) throws Exception {
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class))
                .isFalse();
        UUID requester = UUID.randomUUID();
        UUID courier = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String body = """
                {"createRequestId":"%s","supplierId":"%s","deliveryLocation":"COM2",
                 "description":"Lunch","creditReward":5,"expiryMinutes":10}
                """.formatted(key, UUID.randomUUID());
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        when(credit.reserve(any())).thenAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                waiting.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return new CreditReservationResponse("RESERVED");
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<String> original = executor.submit(() -> create(requester, body));
            try {
                assertThat(waiting.await(15, TimeUnit.SECONDS)).isTrue();
                UUID id = jdbc.queryForObject("SELECT id FROM errands WHERE create_request_id=?", UUID.class, key);
                mvc.perform(post("/orders/" + id + "/retry-creation")
                                .with(jwt().jwt(token -> token.subject(requester.toString()))))
                        .andExpect(status().isOk());
                if (targetStatus.equals("ACCEPTED") || targetStatus.equals("COMPLETED")) {
                    act(id, "accept", courier);
                    if (targetStatus.equals("COMPLETED")) {
                        act(id, "pick-up", courier);
                        act(id, "complete", requester);
                    }
                } else if (targetStatus.equals("CANCELLED")) {
                    act(id, "cancel", requester);
                } else {
                    jdbc.update("UPDATE errands SET expires_at=created_at + interval '1 millisecond' WHERE id=?", id);
                    service.expireOpenOrders();
                }
                if (!targetStatus.equals("ACCEPTED")) {
                    boolean paid = targetStatus.equals("COMPLETED");
                    UUID operation =
                            jdbc.queryForObject("SELECT settlement_event_id FROM errands WHERE id=?", UUID.class, id);
                    service.handleSettlementResult(new CreditSettlementResultEvent(
                            UUID.randomUUID(),
                            1,
                            OffsetDateTime.now(),
                            new CreditSettlementResultEvent.Payload(
                                    operation,
                                    id,
                                    requester,
                                    5,
                                    paid ? "PAID" : "REFUNDED",
                                    paid ? "PAYOUT" : "REFUND",
                                    paid ? courier : null)));
                }
                Map<String, Object> advanced = jdbc.queryForMap("SELECT * FROM errands WHERE id=?", id);
                assertThat(advanced.get("status")).isEqualTo(targetStatus);
                assertThat(advanced.get("credit_state"))
                        .isEqualTo(
                                switch (targetStatus) {
                                    case "ACCEPTED" -> "RESERVED";
                                    case "COMPLETED" -> "SETTLED";
                                    default -> "RELEASED";
                                });
                release.countDown();
                var response = json.readTree(original.get(15, TimeUnit.SECONDS));
                assertThat(response.get("errandId").asString()).isEqualTo(id.toString());
                assertThat(response.get("status").asString()).isEqualTo(targetStatus);
                assertThat(jdbc.queryForMap("SELECT * FROM errands WHERE id=?", id))
                        .isEqualTo(advanced);
                verify(credit, times(2)).reserve(any());
            } finally {
                release.countDown();
                original.get(15, TimeUnit.SECONDS);
            }
        }
    }

    private String create(UUID actor, String body) throws Exception {
        return mvc.perform(post("/orders")
                        .with(jwt().jwt(token -> token.subject(actor.toString())))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void act(UUID id, String action, UUID actor) throws Exception {
        mvc.perform(post("/orders/" + id + "/" + action).with(jwt().jwt(token -> token.subject(actor.toString()))))
                .andExpect(status().isOk());
    }
}
