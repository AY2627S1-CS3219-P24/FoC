package com.cs3219.foc.credit.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("credit.provisioning")
public record CreditProvisioningProperties(
        @DefaultValue("100") @Min(1) int batchSize) {}
