package com.cs3219.foc.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cs3219.foc.order.config.SecurityConfig;
import com.cs3219.foc.order.exception.*;
import com.cs3219.foc.order.model.dto.CreateErrandResponse;
import com.cs3219.foc.order.model.dto.ErrandActionResponse;
import com.cs3219.foc.order.model.entity.CreditState;
import com.cs3219.foc.order.model.entity.ErrandStatus;
import com.cs3219.foc.order.service.OrderService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(OrderControllerTests.TestConfig.class)
class OrderControllerTests {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private OrderService service;

    private MockMvc mvc;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reset(service);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private String body(long reward) {
        return """
                {"createRequestId":"%s","supplierId":"%s","deliveryLocation":"COM2",
                 "description":"Lunch","creditReward":%d,"expiryMinutes":10}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), reward);
    }

    @Test
    void createRequiresLoginAndReturnsAcceptedLocation() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body(5)))
                .andExpect(status().isUnauthorized());
        when(service.create(any()))
                .thenReturn(new CreateErrandResponse(id, ErrandStatus.CREATING, null, "/api/orders/" + id));
        mvc.perform(post("/orders")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(5)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/orders/" + id))
                .andExpect(jsonPath("$.errandId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("CREATING"));
    }

    @Test
    void unreadyAccountHasTheSameSpecificResponseForCreationAndAcceptance() throws Exception {
        when(service.create(any())).thenThrow(new CreditAccountPendingException());
        when(service.accept(id)).thenThrow(new CreditAccountPendingException());
        mvc.perform(post("/orders")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDIT_ACCOUNT_PENDING"));
        mvc.perform(post("/orders/" + id + "/accept").with(jwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDIT_ACCOUNT_PENDING"));
    }

    @Test
    void invalidAmountAndIdsAreRejectedBeforeService() throws Exception {
        mvc.perform(post("/orders")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid request fields"));
        mvc.perform(get("/orders/not-a-uuid").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid request fields"));
        mvc.perform(get("/orders").param("requesterId", "invalid").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid request fields"));
        verifyNoInteractions(service);
    }

    @Test
    void completedOrFailedCreationReplayReturnsCurrentResult() throws Exception {
        when(service.create(any()))
                .thenReturn(new CreateErrandResponse(
                        id, ErrandStatus.CREATE_FAILED, "CREATION_TIMEOUT", "/api/orders/" + id));
        mvc.perform(post("/orders")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creationFailureCode").value("CREATION_TIMEOUT"));
    }

    @Test
    void lifecycleActionsReturnOnlyTheirIdAndCurrentStates() throws Exception {
        var responses = Map.of(
                "accept", new ErrandActionResponse(id, ErrandStatus.ACCEPTED, CreditState.RESERVED),
                "pick-up", new ErrandActionResponse(id, ErrandStatus.PICKED_UP, CreditState.RESERVED),
                "complete", new ErrandActionResponse(id, ErrandStatus.COMPLETED, CreditState.SETTLEMENT_PENDING),
                "cancel", new ErrandActionResponse(id, ErrandStatus.CANCELLED, CreditState.RELEASE_PENDING));
        when(service.accept(id)).thenReturn(responses.get("accept"));
        when(service.pickUp(id)).thenReturn(responses.get("pick-up"));
        when(service.complete(id)).thenReturn(responses.get("complete"));
        when(service.cancel(id)).thenReturn(responses.get("cancel"));

        for (var entry : responses.entrySet()) {
            mvc.perform(post("/orders/" + id + "/" + entry.getKey()).with(jwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$.errandId").value(id.toString()))
                    .andExpect(
                            jsonPath("$.status").value(entry.getValue().status().name()))
                    .andExpect(jsonPath("$.creditState")
                            .value(entry.getValue().creditState().name()));
        }
    }

    @Test
    void domainPermissionsAndStateErrorsHaveDistinctResponses() throws Exception {
        when(service.complete(id)).thenThrow(new AccessDeniedException("Requester required"));
        mvc.perform(post("/orders/" + id + "/complete").with(jwt())).andExpect(status().isForbidden());
        when(service.accept(id)).thenThrow(new OrderConflictException("Already accepted"));
        mvc.perform(post("/orders/" + id + "/accept").with(jwt())).andExpect(status().isConflict());
        when(service.get(id)).thenThrow(new OrderNotFoundException(id));
        mvc.perform(get("/orders/" + id).with(jwt())).andExpect(status().isNotFound());
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({OrderController.class, SecurityConfig.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean
        OrderService orderService() {
            return mock(OrderService.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }
}
