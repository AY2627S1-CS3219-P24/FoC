package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import com.cs3219.foc.credit.model.dto.event.ErrandCreationStartedEvent;
import com.cs3219.foc.credit.service.CreditReservationService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ErrandCreationConsumer {
    @Bean
    public Consumer<ErrandCreationStartedEvent> errandCreationStarted(
            CreditReservationService service, Validator validator) {
        return event -> {
            var violations = validator.validate(event);
            if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
            service.reserveForCreation(event);
        };
    }
}
