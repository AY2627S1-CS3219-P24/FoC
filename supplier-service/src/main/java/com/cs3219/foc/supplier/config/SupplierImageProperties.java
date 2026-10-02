package com.cs3219.foc.supplier.config;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// The path to store the images
@Validated
@ConfigurationProperties(prefix = "supplier.images")
public record SupplierImageProperties(@NotNull Path directory) {}
