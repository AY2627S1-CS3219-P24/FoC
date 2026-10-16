package com.cs3219.foc.credit.service;

import com.cs3219.foc.credit.config.CreditRewardProperties;
import com.cs3219.foc.credit.model.dto.CreditResultDelivery;
import com.cs3219.foc.credit.model.dto.event.CreditReservationResultEvent;
import com.cs3219.foc.credit.model.dto.event.CreditResultEvent;
import com.cs3219.foc.credit.model.dto.event.CreditSettlementResultEvent;
import com.cs3219.foc.credit.model.entity.CreditResultOutbox;
import com.cs3219.foc.credit.repository.CreditResultOutboxRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditResultOutboxService {
    private final CreditResultOutboxRepository repository;
    private final CreditRewardProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<CreditResultDelivery> claimNext() {
        return repository.lockNextPending().map(row -> {
            var token = UUID.randomUUID();
            repository.acquireLease(
                    row.getEventId(), token, properties.leaseDuration().toMillis());
            return new CreditResultDelivery(resultEvent(row), token, row.getAttemptCount() + 1);
        });
    }

    private CreditResultEvent resultEvent(CreditResultOutbox row) {
        if (row.getSettlementOperationId() != null) {
            return new CreditSettlementResultEvent(
                    row.getEventId(),
                    "CreditSettlementResultEvent",
                    1,
                    row.getOccurredAt(),
                    new CreditSettlementResultEvent.Payload(
                            row.getSettlementOperationId(),
                            row.getErrandId(),
                            row.getRequesterId(),
                            row.getAmount(),
                            row.getOutcome(),
                            row.getSettlementType(),
                            row.getCourierId()));
        }
        return new CreditReservationResultEvent(
                row.getEventId(),
                "CreditReservationResultEvent",
                1,
                row.getOccurredAt(),
                new CreditReservationResultEvent.Payload(
                        row.getOperationId(),
                        row.getErrandId(),
                        row.getRequesterId(),
                        row.getAmount(),
                        row.getOutcome()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(CreditResultDelivery delivery) {
        repository.markPublished(delivery.event().eventId(), delivery.leaseToken());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryLater(CreditResultDelivery delivery, String error) {
        long delay = properties.retryDelay().toMillis();
        long maximum = properties.maxRetryDelay().toMillis();
        for (int i = 1; i < delivery.attemptCount() && delay < maximum; i++) {
            delay = delay > maximum / 2 ? maximum : delay * 2;
        }
        repository.retryLater(delivery.event().eventId(), delivery.leaseToken(), Math.min(delay, maximum), error);
    }
}
