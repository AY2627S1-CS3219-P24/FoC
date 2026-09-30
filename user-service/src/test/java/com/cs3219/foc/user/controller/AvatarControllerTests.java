package com.cs3219.foc.user.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cs3219.foc.user.exception.AvatarException;
import com.cs3219.foc.user.model.dto.UserProfileDto;
import com.cs3219.foc.user.service.AvatarService;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@SpringJUnitWebConfig(UserControllerTests.TestConfig.class)
class AvatarControllerTests {
    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AvatarService avatars;

    @Autowired
    private JwtDecoder decoder;

    private MockMvc mvc;
    private final UUID userId = UUID.randomUUID();
    private final MockMultipartFile file = new MockMultipartFile("file", new byte[] {1});

    @BeforeEach
    void setUp() {
        reset(avatars, decoder);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void uploadUsesJwtIdentityAndReturnsProfile() throws Exception {
        var profile = new UserProfileDto(
                userId.toString(), "alex@example.com", "Alex", List.of("USER"), null, null, "/users/me/avatar?v=test");
        when(avatars.upload(eq(userId), any())).thenReturn(profile);
        mvc.perform(multipart(HttpMethod.PUT, "/users/me/avatar")
                        .file(file)
                        .param("userId", UUID.randomUUID().toString())
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(profile.avatarUrl()))
                .andExpect(jsonPath("$.avatarKey").doesNotExist());
        verify(avatars).upload(eq(userId), any());
    }

    @Test
    void retrievesPrivateImageAndIgnoresClientIdentityAndVersion() throws Exception {
        when(avatars.read(userId)).thenReturn(new byte[] {1, 2});
        mvc.perform(get("/users/me/avatar")
                        .param("userId", UUID.randomUUID().toString())
                        .param("v", "untrusted-key")
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[] {1, 2}))
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        verify(avatars).read(userId);
    }

    @Test
    void removalReturnsNoContent() throws Exception {
        mvc.perform(delete("/users/me/avatar").with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(avatars).remove(userId);
    }

    @Test
    void allAvatarOperationsRequireAuthentication() throws Exception {
        mvc.perform(get("/users/me/avatar")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/users/me/avatar")).andExpect(status().isUnauthorized());
        mvc.perform(multipart(HttpMethod.PUT, "/users/me/avatar").file(file)).andExpect(status().isUnauthorized());
        verifyNoInteractions(avatars);
    }

    @Test
    void rejectsInvalidBearerToken() throws Exception {
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid token"));
        mvc.perform(get("/users/me/avatar").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(avatars);
    }

    @Test
    void missingFileReturnsUsefulError() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/users/me/avatar")
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Image file is required"));
        verifyNoInteractions(avatars);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 413, 415, 503})
    void mapsUploadErrorsToHttpResponses(int status) throws Exception {
        when(avatars.upload(any(), any())).thenThrow(new AvatarException(HttpStatus.valueOf(status), "Upload failed"));
        mvc.perform(multipart(HttpMethod.PUT, "/users/me/avatar")
                        .file(file)
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.message").value("Upload failed"));
    }

    @Test
    void mapsMultipartLimitException() throws Exception {
        when(avatars.upload(any(), any())).thenThrow(new MaxUploadSizeExceededException(2097152));
        mvc.perform(multipart(HttpMethod.PUT, "/users/me/avatar")
                        .file(file)
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void noAvatarReturnsNotFound() throws Exception {
        when(avatars.read(userId)).thenThrow(new AvatarException(HttpStatus.NOT_FOUND, "Avatar not found"));
        mvc.perform(get("/users/me/avatar").with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isNotFound());
    }
}
