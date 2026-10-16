package com.cs3219.foc.credit.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cs3219.foc.credit.config.SecurityConfig;
import com.cs3219.foc.credit.exception.CreditAccountPendingException;
import com.cs3219.foc.credit.exception.GlobalExceptionHandler;
import com.cs3219.foc.credit.model.dto.CreditBalanceResponse;
import com.cs3219.foc.credit.service.CreditAccountService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(CreditControllerTests.TestConfig.class)
class CreditControllerTests {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CreditAccountService service;

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

    @Test
    void readsOnlyTheJwtOwnersBalanceEvenWithAnotherUserParameter() throws Exception {
        var owner = UUID.randomUUID();
        when(service.getBalance(owner)).thenReturn(new CreditBalanceResponse(70, 30));
        mvc.perform(get("/credits/me")
                        .param("userId", UUID.randomUUID().toString())
                        .with(jwt().jwt(token -> token.subject(owner.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(70))
                .andExpect(jsonPath("$.reservedBalance").value(30));
        verify(service).getBalance(owner);
        verifyNoMoreInteractions(service);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/credits/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid token"));
        mvc.perform(get("/credits/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedSubject() throws Exception {
        mvc.perform(get("/credits/me").with(jwt().jwt(token -> token.subject("not-a-uuid"))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void reportsPendingAccountWithoutInitializingIt() throws Exception {
        var owner = UUID.randomUUID();
        when(service.getBalance(owner)).thenThrow(new CreditAccountPendingException());
        mvc.perform(get("/credits/me").with(jwt().jwt(token -> token.subject(owner.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDIT_ACCOUNT_PENDING"))
                .andExpect(jsonPath("$.message").value("Credit account setup is pending. Please try again later."));
        verify(service).getBalance(owner);
        verifyNoMoreInteractions(service);
    }

    @Test
    void hasNoEndpointForSelectingAnotherAccount() throws Exception {
        mvc.perform(get("/credits/" + UUID.randomUUID())
                        .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, CreditController.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean
        CreditAccountService creditAccountService() {
            return mock(CreditAccountService.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }
}
