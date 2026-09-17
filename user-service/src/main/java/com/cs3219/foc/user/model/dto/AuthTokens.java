package com.cs3219.foc.user.model.dto;

import java.time.Instant;

public record AuthTokens(String accessToken, String refreshToken, Instant accessTokenExpiresAt) {}
