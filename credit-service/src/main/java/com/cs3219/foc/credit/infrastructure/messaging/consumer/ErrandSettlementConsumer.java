package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import com.cs3219.foc.credit.model.dto.event.ErrandSettlementEvent;
import com.cs3219.foc.credit.service.CreditSettlementService;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ErrandSettlementConsumer {
    @Bean
    public Consumer<ErrandSettlementEvent> errandCancelled(CreditSettlementService service) {
        return service::releaseReservedCredits;
    }

    @Bean
    public Consumer<ErrandSettlementEvent> errandExpired(CreditSettlementService service) {
        return service::releaseReservedCredits;
    }

    @Bean
    public Consumer<ErrandSettlementEvent> errandCompleted(CreditSettlementService service) {
        return service::transferReservedCredits;
    }
}
