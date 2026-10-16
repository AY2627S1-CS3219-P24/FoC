package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import com.cs3219.foc.credit.model.dto.event.UserCreatedEvent;
import com.cs3219.foc.credit.service.CreditProvisioningService;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class UserCreatedConsumer {
    private final CreditProvisioningService service;

    @Bean
    Consumer<UserCreatedEvent> userCreated() {
        return service::accept;
    }
}
