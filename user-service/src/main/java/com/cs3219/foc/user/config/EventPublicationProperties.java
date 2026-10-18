package com.cs3219.foc.user.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("events.publication")
public record EventPublicationProperties(
        @DefaultValue("5s") Duration confirmTimeout,
        @DefaultValue("30s") Duration retryDelay) {}
