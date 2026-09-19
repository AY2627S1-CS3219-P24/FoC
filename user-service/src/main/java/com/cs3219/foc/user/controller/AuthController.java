package com.cs3219.foc.user.controller;

import com.cs3219.foc.user.model.dto.*;
import com.cs3219.foc.user.service.AuthCookieFactory;
import com.cs3219.foc.user.service.AuthService;
import com.cs3219.foc.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AuthController {
    private final UserService userService;
    private final AuthService authService;
    private final AuthCookieFactory authCookieFactory;

    @PostMapping("/auth/register")
    @ResponseStatus(code = HttpStatus.CREATED)
    public UserProfileDto register(@Valid @RequestBody RegisterUserRequest request) {
        return userService.registerUser(request);
    }

    @PostMapping("/auth/login")
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        var tokens = authService.login(request);
        var cookie = authCookieFactory.createRefreshTokenCookie(tokens.refreshToken());

        var accessTokenResponse = new AccessTokenResponse(tokens.accessToken(), tokens.accessTokenExpiresAt());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(accessTokenResponse);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = "refreshToken", required = false) String refreshToken) {
        authService.logout(refreshToken);
        var cookie = authCookieFactory.clearRefreshTokenCookie();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(value = "refreshToken", required = false) String refreshToken) {
        var tokens = authService.refreshAccessToken(refreshToken);
        var cookie = authCookieFactory.createRefreshTokenCookie(tokens.refreshToken());

        var accessTokenResponse = new AccessTokenResponse(tokens.accessToken(), tokens.accessTokenExpiresAt());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(accessTokenResponse);
    }
}
