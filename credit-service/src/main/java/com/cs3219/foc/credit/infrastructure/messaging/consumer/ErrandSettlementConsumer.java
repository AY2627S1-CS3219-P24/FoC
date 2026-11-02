package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import com.cs3219.foc.credit.model.dto.event.ErrandCancelledEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandCompletedEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandExpiredEvent;
import com.cs3219.foc.credit.service.CreditSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ErrandSettlementConsumer {
    private final CreditSettlementService creditSettlementService;

    public void releaseReservedCredits(ErrandCancelledEvent event) {
        creditSettlementService.releaseReservedCredits(event);
    }

    public void releaseReservedCredits(ErrandExpiredEvent event) {
        creditSettlementService.releaseReservedCredits(event);
    }

    public void transferReservedCredits(ErrandCompletedEvent event) {
        creditSettlementService.transferReservedCredits(event);
    }
}
