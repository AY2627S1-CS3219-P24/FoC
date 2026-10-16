package com.cs3219.foc.order.infrastructure.messaging.consumer;

import com.cs3219.foc.order.model.dto.event.CreditReservationResultEvent;
import com.cs3219.foc.order.service.CreditReservationResultService;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CreditReservationResultConsumer {
    @Bean
    public Consumer<CreditReservationResultEvent> creditReservationResult(CreditReservationResultService service) {
        return service::accept;
    }
}
