package com.cs3219.foc.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cs3219.foc.user.config.AuthProperties;
import com.cs3219.foc.user.config.SecurityConfig;
import com.cs3219.foc.user.exception.EntityAlreadyExistsException;
import com.cs3219.foc.user.exception.GlobalExceptionHandler;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.model.dto.UpdateUserProfileRequest;
import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.security.JsonAccessDeniedHandler;
import com.cs3219.foc.user.security.JsonAuthenticationEntryPoint;
import com.cs3219.foc.user.service.AuthCookieFactory;
import com.cs3219.foc.user.service.AvatarService;
import com.cs3219.foc.user.service.PasswordService;
import com.cs3219.foc.user.service.UserService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

@SpringJUnitWebConfig(UserControllerTests.TestConfig.class)
class UserControllerTests {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserService service;

    @Autowired
    private JwtDecoder decoder;

    private MockMvc mvc;
    private final UUID userId = UUID.randomUUID();
    private UserProfileDto profile;

    @BeforeEach
    void setUp() {
        reset(service, decoder);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        profile = new UserProfileDto(
                userId.toString(),
                "alex@example.com",
                "Alex Tan",
                List.of("USER"),
                "+6591235436",
                "School of Computing",
                null);
    }

    @Test
    void readsOnlyAuthenticatedUsersProfile() throws Exception {
        when(service.getUserProfile(userId)).thenReturn(profile);
        mvc.perform(get("/users/me")
                        .param("userId", UUID.randomUUID().toString())
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.name").value("Alex Tan"))
                .andExpect(jsonPath("$.phoneNumber").value("+6591235436"))
                .andExpect(jsonPath("$.faculty").value("School of Computing"))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        verify(service).getUserProfile(userId);
    }

    @Test
    void updatesUsingJwtIdentityAndNormalizedBody() throws Exception {
        when(service.updateUserProfile(eq(userId), any())).thenReturn(profile);
        mvc.perform(put("/users/me")
                        .with(jwt().jwt(token -> token.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" Alex Tan \",\"email\":\" ALEX@EXAMPLE.COM \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alex@example.com"));
        verify(service)
                .updateUserProfile(userId, new UpdateUserProfileRequest("Alex Tan", "alex@example.com", null, null));
    }

    @Test
    void rejectsUnauthenticatedReadAndUpdate() throws Exception {
        mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(put("/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alex\",\"email\":\"alex@example.com\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void normalizesOptionalProfileDetails() throws Exception {
        when(service.updateUserProfile(eq(userId), any())).thenReturn(profile);
        mvc.perform(put("/users/me")
                        .with(jwt().jwt(token -> token.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Alex Tan","email":"alex@example.com",
                                 "phoneNumber":" +65 9123-5436 ","faculty":" School of Computing "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+6591235436"));
        verify(service)
                .updateUserProfile(
                        userId,
                        new UpdateUserProfileRequest(
                                "Alex Tan", "alex@example.com", "+6591235436", "School of Computing"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"{}", "{\"phoneNumber\":null,\"faculty\":null}", "{\"phoneNumber\":\"   \",\"faculty\":\"   \"}"
            })
    void acceptsEmptyOptionalDetails(String optionalFields) throws Exception {
        var fields = optionalFields.substring(1, optionalFields.length() - 1);
        var body =
                "{\"name\":\"Alex Tan\",\"email\":\"alex@example.com\"" + (fields.isEmpty() ? "" : "," + fields) + "}";
        when(service.updateUserProfile(eq(userId), any())).thenReturn(profile);
        mvc.perform(put("/users/me")
                        .with(jwt().jwt(token -> token.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        verify(service)
                .updateUserProfile(userId, new UpdateUserProfileRequest("Alex Tan", "alex@example.com", null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"91235436", "+012345678", "+65abc123", "+1234567890123456", "+1", "---"})
    void rejectsInvalidPhoneNumbers(String phone) throws Exception {
        mvc.perform(put("/users/me")
                        .with(jwt().jwt(token -> token.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"name\":\"Alex\",\"email\":\"alex@example.com\",\"phoneNumber\":\"" + phone + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.phoneNumber").exists());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOversizedFaculty() throws Exception {
        mvc.perform(put("/users/me")
                        .with(jwt().jwt(token -> token.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alex\",\"email\":\"alex@example.com\",\"faculty\":\"" + "a".repeat(256)
                                + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.faculty").exists());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Invalid token"));
        mvc.perform(get("/users/me").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"name\":\"   \",\"email\":\"alex@example.com\"}",
                "{\"email\":\"alex@example.com\"}",
                "{\"name\":null,\"email\":\"alex@example.com\"}"
            })
    void rejectsMissingOrBlankName(String body) throws Exception {
        mvc.perform(put("/users/me")
                        .with(jwt().jwt(token -> token.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "", "   "})
    void rejectsInvalidEmail(String email) throws Exception {
        mvc.perform(put("/users/me")
                        .with(jwt().jwt(token -> token.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alex\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOversizedName() throws Exception {
        mvc.perform(put("/users/me")
                        .with(jwt().jwt(token -> token.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "a".repeat(256) + "\",\"email\":\"alex@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        verifyNoInteractions(service);
    }

    @Test
    void returnsNotFoundForMissingUser() throws Exception {
        when(service.getUserProfile(userId)).thenThrow(new UserNotFoundException("User not found"));
        mvc.perform(get("/users/me").with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    void preservesExistingDuplicateErrorContract() throws Exception {
        when(service.updateUserProfile(eq(userId), any()))
                .thenThrow(new EntityAlreadyExistsException("Email is already associated with another account"));
        mvc.perform(put("/users/me")
                        .with(jwt().jwt(token -> token.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alex\",\"email\":\"taken@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email is already associated with another account"));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
        UserController.class,
        PasswordController.class,
        AvatarController.class,
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class
    })
    static class TestConfig {
        @Bean
        AvatarService avatarService() {
            return mock(AvatarService.class);
        }

        @Bean
        PasswordService passwordService() {
            return mock(PasswordService.class);
        }

        @Bean
        AuthCookieFactory authCookieFactory() {
            var properties = mock(AuthProperties.class);
            when(properties.refreshCookieSecure()).thenReturn(true);
            return new AuthCookieFactory(properties);
        }

        @Bean
        UserDetailsService userDetailsService() {
            // The production configuration also has a username/password provider.
            // Supply it here so its AuthenticationManager has a concrete delegate.
            return mock(UserDetailsService.class);
        }

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new JwtAuthenticationConverter();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
