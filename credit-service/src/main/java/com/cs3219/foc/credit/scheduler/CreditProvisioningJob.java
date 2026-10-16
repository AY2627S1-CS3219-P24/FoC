package com.cs3219.foc.credit.scheduler;

import com.cs3219.foc.credit.config.CreditProvisioningProperties;
import com.cs3219.foc.credit.service.CreditProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "credit.provisioning.enabled", havingValue = "true", matchIfMissing = true)
public class CreditProvisioningJob {
    private final CreditProvisioningService service;
    private final CreditProvisioningProperties properties;

    @Scheduled(fixedDelayString = "${credit.provisioning.interval:5000}")
    public void processPending() {
        try {
            for (int i = 0; i < properties.batchSize(); i++) {
                if (!service.processNext()) return;
            }
        } catch (RuntimeException exception) {
            // The failed transaction rolls back; the next scheduled run retries persisted work.
            log.warn(
                    "Credit provisioning remains pending ({})",
                    exception.getClass().getSimpleName());
        }
    }
}
