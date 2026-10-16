package com.cs3219.foc.credit.scheduler;

import com.cs3219.foc.credit.config.CreditRewardProperties;
import com.cs3219.foc.credit.infrastructure.messaging.producer.CreditResultEventPublisher;
import com.cs3219.foc.credit.service.CreditResultOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "credit.rewards.enabled", havingValue = "true", matchIfMissing = true)
public class CreditResultOutboxJob {
    private final CreditResultOutboxService service;
    private final CreditResultEventPublisher publisher;
    private final CreditRewardProperties properties;

    @Scheduled(fixedDelayString = "${credit.rewards.publish-interval:1000}")
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
                        "Credit result event {} remains pending ({})",
                        delivery.event().eventId(),
                        exception.getClass().getSimpleName());
                // Avoid hammering an unavailable broker with the rest of the batch.
                return;
            }
            service.markPublished(delivery);
        }
    }
}
