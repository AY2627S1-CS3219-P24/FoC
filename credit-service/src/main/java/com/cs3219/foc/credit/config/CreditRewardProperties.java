package com.cs3219.foc.credit.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("credit.rewards")
public record CreditRewardProperties(
        @DefaultValue("50") @Min(1) int publishBatchSize,
        @DefaultValue("200") @Min(1) int processBatchSize,
        @DefaultValue("5s") @DurationMin(millis = 1) Duration confirmTimeout,
        @DefaultValue("30s") @DurationMin(millis = 1) Duration leaseDuration,
        @DefaultValue("5s") @DurationMin(millis = 1) Duration retryDelay,
        @DefaultValue("5m") @DurationMin(millis = 1) Duration maxRetryDelay) {}
