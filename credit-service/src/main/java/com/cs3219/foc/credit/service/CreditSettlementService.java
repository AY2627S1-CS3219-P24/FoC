package com.cs3219.foc.credit.service;

import com.cs3219.foc.credit.config.CreditRewardProperties;
import com.cs3219.foc.credit.model.dto.event.ErrandSettlementEvent;
import com.cs3219.foc.credit.model.entity.CreditSettlementOperation;
import com.cs3219.foc.credit.repository.CreditAccountRepository;
import com.cs3219.foc.credit.repository.CreditReservationRepository;
import com.cs3219.foc.credit.repository.CreditReserveOperationRepository;
import com.cs3219.foc.credit.repository.CreditResultOutboxRepository;
import com.cs3219.foc.credit.repository.CreditSettlementInboxRepository;
import com.cs3219.foc.credit.repository.CreditSettlementOperationRepository;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditSettlementService {
    private final CreditSettlementOperationRepository operations;
    private final CreditSettlementInboxRepository inbox;
    private final CreditReservationRepository reservations;
    private final CreditReserveOperationRepository reserveOperations;
    private final CreditAccountRepository accounts;
    private final CreditResultOutboxRepository outbox;
    private final CreditRewardProperties properties;

    @Transactional
    public void accept(ErrandSettlementEvent event, String expectedEventType) {
        if (event == null
                || event.eventId() == null
                || event.occurredAt() == null
                || event.schemaVersion() != 1
                || event.eventType() == null
                || !event.eventType().equals(expectedEventType)
                || event.payload() == null) {
            throw new IllegalArgumentException("Unsupported or incomplete errand settlement event");
        }
        var p = event.payload();
        boolean payout = "ErrandCompletedEvent".equals(event.eventType());
        if (p.operationId() == null
                || p.reservationOperationId() == null
                || p.errandId() == null
                || p.requesterId() == null
                || p.amount() <= 0
                || payout != (p.courierId() != null)) {
            throw new IllegalArgumentException(
                    "Settlement requires reservation IDs, a positive amount, and a courier only for completion");
        }
        operations.accept(
                p.operationId(),
                p.reservationOperationId(),
                p.errandId(),
                p.requesterId(),
                p.amount(),
                p.courierId(),
                event.eventType());
        var existing = operations.findById(p.operationId()).orElseThrow();
        if (!existing.getReservationOperationId().equals(p.reservationOperationId())
                || !existing.getErrandId().equals(p.errandId())
                || !existing.getRequesterId().equals(p.requesterId())
                || existing.getAmount() != p.amount()
                || !Objects.equals(existing.getCourierId(), p.courierId())
                || !existing.getEventType().equals(event.eventType())) {
            throw new IllegalArgumentException("Settlement operation ID cannot be reused with changed data");
        }
        if (inbox.accept(event.eventId(), p.operationId()) == 0
                && !inbox.findById(event.eventId())
                        .orElseThrow()
                        .getOperationId()
                        .equals(p.operationId())) {
            throw new IllegalArgumentException("Event ID belongs to another settlement operation");
        }
    }

    @Transactional
    public boolean processNext() {
        var pending = inbox.lockNextPending();
        if (pending.isEmpty()) return false;
        var delivery = pending.get();
        var operation = operations.lockById(delivery.getOperationId()).orElseThrow();
        if (!"PENDING".equals(operation.getOutcome())) {
            outbox.replaySettlement(operation.getOperationId());
            inbox.complete(delivery.getEventId());
            return true;
        }
        // Lock the reservation before any accounts. Concurrent terminal requests serialize here.
        var stored = reservations.lockByErrandId(operation.getErrandId());
        if (stored.isEmpty()) {
            var reserve = reserveOperations.findByErrandId(operation.getErrandId());
            if (reserve.isPresent()
                    && !matches(
                            operation,
                            reserve.get().getOperationId(),
                            reserve.get().getRequesterId(),
                            reserve.get().getAmount())) {
                finish(operation, delivery.getEventId(), "RESERVATION_MISMATCH");
            } else if (reserve.isPresent()
                    && "INSUFFICIENT_BALANCE".equals(reserve.get().getOutcome())) {
                finish(operation, delivery.getEventId(), "NO_RESERVATION");
            } else {
                defer(delivery.getEventId());
            }
            return true;
        }
        var reservation = stored.get();
        if (!matches(operation, reservation.getOperationId(), reservation.getUserId(), reservation.getAmount())) {
            finish(operation, delivery.getEventId(), "RESERVATION_MISMATCH");
            return true;
        }
        boolean payout = "ErrandCompletedEvent".equals(operation.getEventType());
        String targetState = payout ? "PAID" : "REFUNDED";
        if (!"RESERVED".equals(reservation.getState())) {
            boolean sameEffect = targetState.equals(reservation.getState())
                    && Objects.equals(reservation.getCourierId(), operation.getCourierId());
            finish(operation, delivery.getEventId(), sameEffect ? targetState : "TERMINAL_CONFLICT");
            return true;
        }
        UUID recipient = payout ? operation.getCourierId() : reservation.getUserId();
        // Every transfer uses this UUID order, including payments in opposite directions.
        var accountIds = Stream.of(reservation.getUserId(), recipient)
                .distinct()
                .sorted()
                .toList();
        for (var id : accountIds) {
            if (accounts.lockByUserId(id).isEmpty()) {
                defer(delivery.getEventId());
                return true;
            }
        }
        if (payout) {
            accounts.debitTotal(reservation.getUserId(), reservation.getAmount());
            accounts.creditTotal(recipient, reservation.getAmount());
        }
        reservations.settle(reservation.getErrandId(), targetState, operation.getCourierId());
        finish(operation, delivery.getEventId(), targetState);
        return true;
    }

    private boolean matches(
            CreditSettlementOperation operation, UUID reservationOperation, UUID requester, long amount) {
        return operation.getReservationOperationId().equals(reservationOperation)
                && operation.getRequesterId().equals(requester)
                && operation.getAmount() == amount;
    }

    private void defer(UUID eventId) {
        inbox.retryLater(eventId, properties.retryDelay().toMillis());
    }

    private void finish(CreditSettlementOperation operation, UUID eventId, String outcome) {
        operations.complete(operation.getOperationId(), outcome);
        outbox.enqueueSettlement(UUID.randomUUID(), operation.getOperationId());
        inbox.complete(eventId);
    }
}
