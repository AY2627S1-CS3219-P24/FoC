package com.cs3219.foc.order.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cs3219.foc.order.config.SecurityConfig;
import com.cs3219.foc.order.exception.GlobalExceptionHandler;
import com.cs3219.foc.order.exception.IdempotencyConflictException;
import com.cs3219.foc.order.model.dto.ErrandResponse;
import com.cs3219.foc.order.service.ErrandService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(ErrandControllerTests.TestConfig.class)
class ErrandControllerTests {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ErrandService service;

    @Autowired
    private JwtDecoder decoder;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service, decoder);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private final String body =
            "{\"pickup\":\"COM3\",\"destination\":\"UTown\",\"description\":\"Collect lunch\",\"reward\":80}";

    private ErrandResponse response(UUID owner, String state) {
        return new ErrandResponse(
                UUID.randomUUID(), owner, "COM3", "UTown", "Collect lunch", 80, state, null, OffsetDateTime.now());
    }

    @Test
    void creationUsesJwtRequesterAndReturnsImmediateFundingDecision() throws Exception {
        var owner = UUID.randomUUID();
        var e = response(owner, "OPEN");
        when(service.create(eq(owner), eq("key"), any(), eq("token"))).thenReturn(e);
        mvc.perform(post("/orders")
                        .with(jwt().jwt(t -> t.subject(owner.toString())))
                        .header("Idempotency-Key", "key")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/orders/" + e.id()))
                .andExpect(jsonPath("$.status").value("OPEN"));
        verify(service).create(eq(owner), eq("key"), any(), eq("token"));
    }

    @Test
    void requiresAuthenticationAndValidSubject() throws Exception {
        mvc.perform(post("/orders")
                        .header("Idempotency-Key", "key")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/orders")
                        .with(jwt().jwt(t -> t.subject("bad-id")))
                        .header("Idempotency-Key", "key")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void requiresKeyAndPositiveReward() throws Exception {
        var auth = jwt().jwt(t -> t.subject(UUID.randomUUID().toString()));
        mvc.perform(post("/orders").with(auth).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/orders")
                        .with(auth)
                        .header("Idempotency-Key", " ")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/orders")
                        .with(auth)
                        .header("Idempotency-Key", "key")
                        .contentType("application/json")
                        .content(body.replace("80", "0")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void changedKeyPayloadReturnsConflict() throws Exception {
        when(service.create(any(), any(), any(), any())).thenThrow(new IdempotencyConflictException());
        mvc.perform(post("/orders")
                        .with(jwt().jwt(t -> t.subject(UUID.randomUUID().toString())))
                        .header("Idempotency-Key", "key")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void replayOfFinishedCreationReturnsCurrentState() throws Exception {
        var owner = UUID.randomUUID();
        when(service.create(eq(owner), any(), any(), any())).thenReturn(response(owner, "OPEN"));
        mvc.perform(post("/orders")
                        .with(jwt().jwt(t -> t.subject(owner.toString())))
                        .header("Idempotency-Key", "key")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void pollingUsesJwtOwnerAndReturnsCurrentState() throws Exception {
        var owner = UUID.randomUUID();
        var e = response(owner, "CREDIT_REJECTED");
        when(service.get(owner, e.id())).thenReturn(e);
        mvc.perform(get("/orders/" + e.id())
                        .param("requesterId", UUID.randomUUID().toString())
                        .with(jwt().jwt(t -> t.subject(owner.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREDIT_REJECTED"));
        verify(service).get(owner, e.id());
    }

    @Test
    void insufficientCreditsReturnImmediateRejection() throws Exception {
        var owner = UUID.randomUUID();
        when(service.create(eq(owner), any(), any(), any())).thenReturn(response(owner, "CREDIT_REJECTED"));
        mvc.perform(post("/orders")
                        .with(jwt().jwt(t -> t.subject(owner.toString())))
                        .header("Idempotency-Key", "key")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("CREDIT_REJECTED"));
    }

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, ErrandController.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean
        ErrandService errandService() {
            return mock(ErrandService.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }
}
