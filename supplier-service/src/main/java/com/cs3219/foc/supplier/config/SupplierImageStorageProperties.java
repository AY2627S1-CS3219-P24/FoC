package com.cs3219.foc.supplier.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("storage.supplier-images")
public record SupplierImageStorageProperties(
        @DefaultValue("10m") @NotNull @DurationMin(seconds = 1)
        Duration readUrlTtl) {}
