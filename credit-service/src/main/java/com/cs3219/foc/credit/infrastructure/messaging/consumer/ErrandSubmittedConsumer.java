package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import com.cs3219.foc.credit.model.dto.event.ErrandSubmittedEvent;
import com.cs3219.foc.credit.service.CreditReservationService;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ErrandSubmittedConsumer {
    @Bean
    public Consumer<ErrandSubmittedEvent> errandSubmitted(CreditReservationService service) {
        return service::accept;
    }
}
