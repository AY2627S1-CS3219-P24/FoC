package com.cs3219.foc.user.scheduler;

import com.cs3219.foc.user.service.AvatarCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AvatarCleanupJob {
    private final AvatarCleanupService cleanup;

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void cleanUnusedAvatars() {
        cleanup.cleanPending();
    }
}
