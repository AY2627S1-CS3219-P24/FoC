package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import com.cs3219.foc.credit.model.dto.event.ErrandCancelledEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandCompletedEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandExpiredEvent;
import com.cs3219.foc.credit.service.CreditSettlementService;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ErrandSettlementConsumer {
    @Bean
    public Consumer<ErrandCancelledEvent> errandCancelled(CreditSettlementService service) {
        return service::releaseReservedCredits;
    }

    @Bean
    public Consumer<ErrandExpiredEvent> errandExpired(CreditSettlementService service) {
        return service::releaseReservedCredits;
    }

    @Bean
    public Consumer<ErrandCompletedEvent> errandCompleted(CreditSettlementService service) {
        return service::transferReservedCredits;
    }
}
