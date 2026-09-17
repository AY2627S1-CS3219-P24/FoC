package com.cs3219.foc.user.model.dto;

import java.time.Instant;

public record AccessTokenDto(String accessToken, Instant expiresAt) {}
