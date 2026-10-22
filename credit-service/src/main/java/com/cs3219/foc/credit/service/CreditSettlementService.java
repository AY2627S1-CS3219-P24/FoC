package com.cs3219.foc.credit.service;

import static com.cs3219.foc.credit.model.entity.ReservationState.*;

import com.cs3219.foc.credit.exception.CreditAccountPendingException;
import com.cs3219.foc.credit.model.dto.event.CreditSettlementResultEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandCancelledEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandCompletedEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandExpiredEvent;
import com.cs3219.foc.credit.model.entity.CreditReservation;
import com.cs3219.foc.credit.model.entity.ReservationState;
import com.cs3219.foc.credit.repository.*;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditSettlementService {
    private final CreditReservationRepository creditReservationRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionLogRepository creditTransactionLogRepository;
    private final ApplicationEventPublisher events;

    @Transactional
    public void releaseReservedCredits(ErrandCancelledEvent event) {
        var p = event.payload();
        releaseReservedCredits(
                new Settlement(event.eventId(), p.errandId(), p.requesterId(), p.amount(), null, event.occurredAt()));
    }

    @Transactional
    public void releaseReservedCredits(ErrandExpiredEvent event) {
        var p = event.payload();
        releaseReservedCredits(
                new Settlement(event.eventId(), p.errandId(), p.requesterId(), p.amount(), null, event.occurredAt()));
    }

    private void releaseReservedCredits(Settlement p) {
        var reservation = lockReservation(p, "REFUND");
        var outcome = existingOutcome(reservation, p, REFUNDED);
        if (outcome == null) {
            creditAccountRepository
                    .lockByUserId(reservation.getUserId())
                    .orElseThrow(CreditAccountPendingException::new);
            creditReservationRepository.settle(p.errandId(), REFUNDED, null);
            creditTransactionLogRepository.record(
                    p.operationId(), p.errandId(), p.requesterId(), null, p.amount(), "REFUND");
            outcome = REFUNDED.name();
        }
        publish(p, outcome, "REFUND");
    }

    @Transactional
    public void transferReservedCredits(ErrandCompletedEvent event) {
        var payload = event.payload();
        var p = new Settlement(
                event.eventId(),
                payload.errandId(),
                payload.requesterId(),
                payload.amount(),
                payload.courierId(),
                event.occurredAt());
        var reservation = lockReservation(p, "PAYMENT");
        var outcome = existingOutcome(reservation, p, PAID);
        if (outcome == null) {
            // Use the same account order for transfers in either direction.
            for (var user : Stream.of(reservation.getUserId(), p.courierId())
                    .distinct()
                    .sorted()
                    .toList()) {
                creditAccountRepository.lockByUserId(user).orElseThrow(CreditAccountPendingException::new);
            }
            creditAccountRepository.debitTotal(reservation.getUserId(), reservation.getAmount());
            creditAccountRepository.creditTotal(p.courierId(), reservation.getAmount());
            creditReservationRepository.settle(p.errandId(), PAID, p.courierId());
            creditTransactionLogRepository.record(
                    p.operationId(), p.errandId(), p.requesterId(), p.courierId(), p.amount(), "PAYMENT");
            outcome = PAID.name();
        }
        publish(p, outcome, "PAYOUT");
    }

    private CreditReservation lockReservation(Settlement p, String type) {
        // Serialize terminal decisions before locking the affected accounts.
        var reservation = creditReservationRepository
                .lockByErrandId(p.errandId())
                .orElseThrow(() -> new IllegalStateException("Reservation is not ready; retry settlement"));
        var previous = creditTransactionLogRepository.findById(p.operationId());
        if (previous.isPresent()) {
            var log = previous.get();
            if (!log.getErrandId().equals(p.errandId())
                    || !log.getUserId().equals(p.requesterId())
                    || log.getAmount() != p.amount()
                    || !Objects.equals(log.getCourierId(), p.courierId())
                    || !log.getTransactionType().equals(type)) {
                throw new IllegalArgumentException("Errand event ID cannot be reused with changed data");
            }
        }
        return reservation;
    }

    private String existingOutcome(CreditReservation reservation, Settlement p, ReservationState target) {
        if (!reservation.getUserId().equals(p.requesterId()) || reservation.getAmount() != p.amount()) {
            return "RESERVATION_MISMATCH";
        }
        if (reservation.getState() == INSUFFICIENT_BALANCE) {
            return "NO_RESERVATION";
        }
        if (reservation.getState() != RESERVED) {
            boolean sameEffect =
                    target == reservation.getState() && Objects.equals(reservation.getCourierId(), p.courierId());
            return sameEffect ? target.name() : "TERMINAL_CONFLICT";
        }
        return null;
    }

    private void publish(Settlement p, String outcome, String settlementType) {
        events.publishEvent(new CreditSettlementResultEvent(
                UUID.randomUUID(),
                1,
                p.occurredAt(),
                new CreditSettlementResultEvent.Payload(
                        p.operationId(),
                        p.errandId(),
                        p.requesterId(),
                        p.amount(),
                        outcome,
                        settlementType,
                        p.courierId())));
    }
    // The Order event ID identifies the credit operation; Order does not create a settlement command.
    private record Settlement(
            UUID operationId,
            UUID errandId,
            UUID requesterId,
            long amount,
            UUID courierId,
            OffsetDateTime occurredAt) {}
}
