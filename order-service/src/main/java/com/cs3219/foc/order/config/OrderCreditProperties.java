package com.cs3219.foc.order.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("order.credit")
public record OrderCreditProperties(
        @DefaultValue("http://localhost:8083/api") String baseUrl,
        @DefaultValue("5s") Duration timeout) {}
