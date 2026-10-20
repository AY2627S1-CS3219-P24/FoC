package com.cs3219.foc.order.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("orders")
public record OrderProperties(
        @DefaultValue("30s") @NotNull Duration creationWait,
        @DefaultValue("100") @Min(1) @Max(500) int expiryBatchSize) {
    public OrderProperties {
        if (creationWait != null && (creationWait.isNegative() || creationWait.isZero())) {
            throw new IllegalArgumentException("orders.creation-wait must be positive");
        }
    }
}
