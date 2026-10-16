package com.cs3219.foc.order.service;

import com.cs3219.foc.order.config.OrderCreditProperties;
import com.cs3219.foc.order.model.dto.ErrandSubmissionDelivery;
import com.cs3219.foc.order.model.dto.event.ErrandSubmittedEvent;
import com.cs3219.foc.order.model.entity.ErrandSubmissionOutbox;
import com.cs3219.foc.order.repository.ErrandRepository;
import com.cs3219.foc.order.repository.ErrandSubmissionOutboxRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ErrandSubmissionOutboxService {
    private final ErrandSubmissionOutboxRepository repository;
    private final OrderCreditProperties properties;
    private final ErrandRepository errands;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ErrandSubmissionDelivery> claimNext() {
        return repository.lockNextPending().map(row -> {
            var token = UUID.randomUUID();
            repository.acquireLease(
                    row.getEventId(), token, properties.leaseDuration().toMillis());
            return new ErrandSubmissionDelivery(submissionEvent(row), token, row.getAttemptCount() + 1);
        });
    }

    private ErrandSubmittedEvent submissionEvent(ErrandSubmissionOutbox row) {
        var errand = errands.findById(row.getErrandId()).orElseThrow();
        return new ErrandSubmittedEvent(
                row.getEventId(),
                "ErrandSubmittedEvent",
                1,
                row.getOccurredAt(),
                new ErrandSubmittedEvent.Payload(
                        errand.getReservationOperationId(),
                        errand.getId(),
                        errand.getRequesterId(),
                        errand.getReward()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(ErrandSubmissionDelivery delivery) {
        repository.markPublished(delivery.event().eventId(), delivery.leaseToken());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryLater(ErrandSubmissionDelivery delivery, String error) {
        long delay = properties.retryDelay().toMillis();
        long maximum = properties.maxRetryDelay().toMillis();
        for (int i = 1; i < delivery.attemptCount() && delay < maximum; i++) {
            delay = delay > maximum / 2 ? maximum : delay * 2;
        }
        repository.retryLater(delivery.event().eventId(), delivery.leaseToken(), Math.min(delay, maximum), error);
    }
}
