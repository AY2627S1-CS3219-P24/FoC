package com.cs3219.foc.user.scheduler;

import com.cs3219.foc.user.service.UserProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "user.provisioning.enabled", havingValue = "true", matchIfMissing = true)
public class UserProvisioningBackfillJob {
    private final UserProvisioningService service;

    @Scheduled(fixedDelayString = "${user.provisioning.backfill-interval:30000}")
    public void reconcileUsers() {
        service.backfill();
    }
}
