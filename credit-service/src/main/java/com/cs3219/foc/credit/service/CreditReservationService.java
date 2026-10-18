package com.cs3219.foc.credit.service;

import com.cs3219.foc.credit.config.CreditRewardProperties;
import com.cs3219.foc.credit.model.dto.event.ErrandSubmittedEvent;
import com.cs3219.foc.credit.repository.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditReservationService {
    private final CreditReserveOperationRepository operations;
    private final CreditReserveInboxRepository inbox;
    private final CreditAccountRepository accounts;
    private final CreditReservationRepository reservations;
    private final CreditResultOutboxRepository outbox;
    private final CreditRewardProperties properties;

    @Transactional
    public void accept(ErrandSubmittedEvent event) {
        if (event == null
                || event.eventId() == null
                || event.occurredAt() == null
                || event.schemaVersion() != 1
                || !"ErrandSubmittedEvent".equals(event.eventType())
                || event.payload() == null) {
            throw new IllegalArgumentException("Unsupported or incomplete errand-submitted event");
        }
        var p = event.payload();
        if (p.operationId() == null || p.errandId() == null || p.requesterId() == null || p.amount() <= 0) {
            throw new IllegalArgumentException("Reservation requires IDs and a positive credit amount");
        }
        operations.accept(p.operationId(), p.errandId(), p.requesterId(), p.amount());
        var operation = operations
                .findById(p.operationId())
                .orElseThrow(
                        () -> new IllegalArgumentException("Errand already belongs to another reservation operation"));
        if (!operation.getErrandId().equals(p.errandId())
                || !operation.getRequesterId().equals(p.requesterId())
                || operation.getAmount() != p.amount()) {
            throw new IllegalArgumentException("Reservation operation ID cannot be reused with changed data");
        }
        if (inbox.accept(event.eventId(), p.operationId()) == 0
                && !inbox.findById(event.eventId())
                        .orElseThrow()
                        .getOperationId()
                        .equals(p.operationId())) {
            throw new IllegalArgumentException("Event ID belongs to another reservation operation");
        }
    }

    @Transactional
    public boolean processNext() {
        var pending = inbox.lockNextPending();
        if (pending.isEmpty()) return false;
        var delivery = pending.get();
        // Every reservation locks operation first, then its single account.
        var operation = operations.lockById(delivery.getOperationId()).orElseThrow();
        if (!"PENDING".equals(operation.getOutcome())) {
            // A retried request receives the original result with the same event ID.
            outbox.replay(operation.getOperationId());
            inbox.complete(delivery.getEventId());
            return true;
        }
        var account = accounts.lockByUserId(operation.getRequesterId());
        if (account.isEmpty()) {
            // Provisioning can arrive later. Defer this row so other users can proceed.
            inbox.retryLater(delivery.getEventId(), properties.retryDelay().toMillis());
            return true;
        }
        String outcome = "INSUFFICIENT_BALANCE";
        if (accounts.findBalanceByUserId(operation.getRequesterId())
                        .orElseThrow()
                        .availableBalance()
                >= operation.getAmount()) {
            reservations.reserve(
                    operation.getErrandId(),
                    operation.getOperationId(),
                    operation.getRequesterId(),
                    operation.getAmount());
            outcome = "RESERVED";
        }
        operations.complete(operation.getOperationId(), outcome);
        outbox.enqueue(UUID.randomUUID(), operation.getOperationId());
        inbox.complete(delivery.getEventId());
        return true;
    }
}
