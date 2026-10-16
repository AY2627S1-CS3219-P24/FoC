package com.cs3219.foc.credit.scheduler;

import com.cs3219.foc.credit.config.CreditRewardProperties;
import com.cs3219.foc.credit.service.CreditSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "credit.rewards.enabled", havingValue = "true", matchIfMissing = true)
public class CreditSettlementJob {
    private final CreditSettlementService service;
    private final CreditRewardProperties properties;

    @Scheduled(fixedDelayString = "${credit.rewards.settlement-interval:5000}")
    public void processPending() {
        try {
            for (int i = 0; i < properties.processBatchSize(); i++) {
                if (!service.processNext()) return;
            }
        } catch (RuntimeException exception) {
            // The failed transaction rolls back; the next scheduled run retries persisted work.
            log.warn(
                    "Credit settlement remains pending ({})",
                    exception.getClass().getSimpleName());
        }
    }
}
