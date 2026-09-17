package com.cs3219.foc.user.service;

import com.cs3219.foc.user.exception.InvalidRefreshTokenException;
import com.cs3219.foc.user.model.dto.AuthTokens;
import com.cs3219.foc.user.model.dto.LoginRequest;
import com.cs3219.foc.user.repository.UserRepository;
import com.cs3219.foc.user.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final UserRepository userRepository;

    public AuthTokens login(LoginRequest request) {
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));

        if (!(authentication.getPrincipal() instanceof CustomUserDetails user)) {
            throw new RuntimeException("Authentication failed");
        }

        var userId = user.getUserId();
        var roles = user.getRoles();

        var accessToken = tokenService.createAccessToken(userId, roles);
        var refreshToken = tokenService.createRefreshToken(userId);

        return new AuthTokens(accessToken.accessToken(), refreshToken, accessToken.expiresAt());
    }

    @Transactional
    public AuthTokens refreshAccessToken(String refreshToken) {
        var storedRefreshToken = tokenService.validateRefreshToken(refreshToken);
        var userId = storedRefreshToken.getUserId();
        var user = userRepository.findById(userId).orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));

        var accessToken = tokenService.createAccessToken(userId, user.getRoles());
        var newRefreshToken = tokenService.rotateRefreshToken(storedRefreshToken);

        log.info("Refresh and access tokens created for userId: {}", userId);
        return new AuthTokens(accessToken.accessToken(), newRefreshToken, accessToken.expiresAt());
    }

    public void logout(String refreshToken) {
        tokenService.revokeRefreshToken(refreshToken);
    }
}
