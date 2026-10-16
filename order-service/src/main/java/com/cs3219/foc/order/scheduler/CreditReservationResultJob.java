package com.cs3219.foc.order.scheduler;

import com.cs3219.foc.order.config.OrderCreditProperties;
import com.cs3219.foc.order.service.CreditReservationResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "order.credit.enabled", havingValue = "true", matchIfMissing = true)
public class CreditReservationResultJob {
    private final CreditReservationResultService service;
    private final OrderCreditProperties properties;

    @Scheduled(fixedDelayString = "${order.credit.interval:5000}")
    public void processPending() {
        try {
            for (int i = 0; i < properties.processBatchSize(); i++) {
                if (!service.processNext()) return;
            }
        } catch (RuntimeException exception) {
            // The failed transaction rolls back; the next scheduled run retries persisted work.
            log.warn(
                    "Credit reservation result remains pending ({})",
                    exception.getClass().getSimpleName());
        }
    }
}
