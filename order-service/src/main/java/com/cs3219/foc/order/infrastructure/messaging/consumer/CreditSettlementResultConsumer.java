package com.cs3219.foc.order.infrastructure.messaging.consumer;

import com.cs3219.foc.order.model.dto.event.CreditSettlementResultEvent;
import com.cs3219.foc.order.service.OrderService;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CreditSettlementResultConsumer {
    @Bean
    public Consumer<CreditSettlementResultEvent> creditSettlementResult(OrderService service) {
        return event -> {
            if (event.eventId() == null
                    || event.occurredAt() == null
                    || event.payload() == null
                    || event.schemaVersion() != 1
                    || !"CreditSettlementResultEvent".equals(event.eventType())) {
                throw new IllegalArgumentException("Invalid CreditSettlementResultEvent envelope");
            }
            service.handleSettlementResult(event);
        };
    }
}
