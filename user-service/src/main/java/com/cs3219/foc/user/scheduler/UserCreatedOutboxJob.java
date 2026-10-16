package com.cs3219.foc.user.scheduler;

import com.cs3219.foc.user.config.UserProvisioningProperties;
import com.cs3219.foc.user.infrastructure.messaging.producer.UserCreatedEventPublisher;
import com.cs3219.foc.user.service.UserProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "user.provisioning.enabled", havingValue = "true", matchIfMissing = true)
public class UserCreatedOutboxJob {
    private final UserProvisioningService service;
    private final UserCreatedEventPublisher publisher;
    private final UserProvisioningProperties properties;

    @Scheduled(fixedDelayString = "${user.provisioning.publish-interval:1000}")
    public void publishPending() {
        for (int i = 0; i < properties.publishBatchSize(); i++) {
            var pending = service.claimNext();
            if (pending.isEmpty()) return;
            var delivery = pending.get();
            try {
                publisher.publish(delivery.event());
            } catch (RuntimeException exception) {
                // Do not persist broker exception messages: connection errors can contain credentials.
                service.retryLater(delivery, exception.getClass().getSimpleName());
                log.warn(
                        "User-created event {} remains pending ({})",
                        delivery.event().eventId(),
                        exception.getClass().getSimpleName());
                // Avoid hammering an unavailable broker with the rest of the batch.
                return;
            }
            service.markPublished(delivery);
        }
    }
}
