package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import com.cs3219.foc.credit.model.dto.event.ErrandSettlementEvent;
import com.cs3219.foc.credit.service.CreditSettlementService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ErrandSettlementConsumer {
    @Bean
    public Consumer<ErrandSettlementEvent> errandCancelled(CreditSettlementService service, Validator validator) {
        return consumer(service, validator, "ErrandCancelledEvent");
    }

    @Bean
    public Consumer<ErrandSettlementEvent> errandExpired(CreditSettlementService service, Validator validator) {
        return consumer(service, validator, "ErrandExpiredEvent");
    }

    @Bean
    public Consumer<ErrandSettlementEvent> errandCompleted(CreditSettlementService service, Validator validator) {
        return consumer(service, validator, "ErrandCompletedEvent");
    }

    private Consumer<ErrandSettlementEvent> consumer(
            CreditSettlementService service, Validator validator, String type) {
        return event -> {
            var violations = validator.validate(Objects.requireNonNull(event, "Settlement event is required"));
            if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
            if (!type.equals(event.eventType())) throw new IllegalArgumentException("Unexpected settlement event type");
            service.settle(event);
        };
    }
}
