package com.cs3219.foc.order.infrastructure.messaging.consumer;

import com.cs3219.foc.order.model.dto.event.CreditReservationResultEvent;
import com.cs3219.foc.order.service.OrderService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CreditReservationResultConsumer {
    @Bean
    public Consumer<CreditReservationResultEvent> creditReservationResult(OrderService service, Validator validator) {
        return event -> {
            var violations = validator.validate(event);
            if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
            service.handleReservationResult(event);
        };
    }
}
