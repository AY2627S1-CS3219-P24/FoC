package com.cs3219.foc.user.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("user.provisioning")
public record UserProvisioningProperties(
        @DefaultValue("50") @Min(1) int publishBatchSize,
        @DefaultValue("200") @Min(1) int backfillBatchSize,
        @DefaultValue("5s") @DurationMin(millis = 1) Duration confirmTimeout,
        @DefaultValue("30s") @DurationMin(millis = 1) Duration leaseDuration,
        @DefaultValue("5s") @DurationMin(millis = 1) Duration retryDelay,
        @DefaultValue("5m") @DurationMin(millis = 1) Duration maxRetryDelay) {}
