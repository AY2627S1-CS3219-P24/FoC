package com.cs3219.foc.user.service;

import com.cs3219.foc.user.config.UserProvisioningProperties;
import com.cs3219.foc.user.model.dto.UserCreatedDelivery;
import com.cs3219.foc.user.model.dto.event.UserCreatedEvent;
import com.cs3219.foc.user.repository.UserCreatedOutboxRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProvisioningService {
    private final UserCreatedOutboxRepository repository;
    private final UserProvisioningProperties properties;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUser(UUID userId) {
        repository.recordUser(userId);
    }

    @Transactional
    public int backfill() {
        return repository.backfill(properties.backfillBatchSize());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UserCreatedDelivery> claimNext() {
        return repository.lockNextPending().map(row -> {
            var token = UUID.randomUUID();
            repository.acquireLease(
                    row.getEventId(), token, properties.leaseDuration().toMillis());
            return new UserCreatedDelivery(
                    UserCreatedEvent.of(row.getEventId(), row.getUserId(), row.getOccurredAt()),
                    token,
                    row.getAttemptCount() + 1);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UserCreatedDelivery delivery) {
        repository.markPublished(delivery.event().eventId(), delivery.leaseToken());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryLater(UserCreatedDelivery delivery, String error) {
        long delay = properties.retryDelay().toMillis();
        long maximum = properties.maxRetryDelay().toMillis();
        for (int i = 1; i < delivery.attemptCount() && delay < maximum; i++) {
            delay = delay > maximum / 2 ? maximum : delay * 2;
        }
        repository.retryLater(delivery.event().eventId(), delivery.leaseToken(), Math.min(delay, maximum), error);
    }
}
