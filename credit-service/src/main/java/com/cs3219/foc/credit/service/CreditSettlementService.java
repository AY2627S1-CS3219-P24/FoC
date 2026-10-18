package com.cs3219.foc.credit.service;

import com.cs3219.foc.credit.exception.CreditAccountPendingException;
import com.cs3219.foc.credit.model.dto.event.CreditSettlementResultEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandSettlementEvent;
import com.cs3219.foc.credit.model.entity.CreditReservation;
import com.cs3219.foc.credit.repository.*;
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
    private final CreditReservationRepository reservations;
    private final CreditAccountRepository accounts;
    private final CreditTransactionLogRepository transactions;
    private final ApplicationEventPublisher events;

    @Transactional
    public void releaseReservedCredits(ErrandSettlementEvent event) {
        var p = event.payload();
        var reservation = lockReservation(event, "REFUND");
        var outcome = existingOutcome(reservation, p, "REFUNDED");
        if (outcome == null) {
            accounts.lockByUserId(reservation.getUserId()).orElseThrow(CreditAccountPendingException::new);
            reservations.settle(p.errandId(), "REFUNDED", null);
            transactions.record(p.operationId(), p.errandId(), p.requesterId(), null, p.amount(), "REFUND");
            outcome = "REFUNDED";
        }
        publish(event, outcome, "REFUND");
    }

    @Transactional
    public void transferReservedCredits(ErrandSettlementEvent event) {
        var p = event.payload();
        var reservation = lockReservation(event, "PAYMENT");
        var outcome = existingOutcome(reservation, p, "PAID");
        if (outcome == null) {
            // Use the same account order for transfers in either direction.
            for (var user : Stream.of(reservation.getUserId(), p.courierId())
                    .distinct()
                    .sorted()
                    .toList()) {
                accounts.lockByUserId(user).orElseThrow(CreditAccountPendingException::new);
            }
            accounts.debitTotal(reservation.getUserId(), reservation.getAmount());
            accounts.creditTotal(p.courierId(), reservation.getAmount());
            reservations.settle(p.errandId(), "PAID", p.courierId());
            transactions.record(p.operationId(), p.errandId(), p.requesterId(), p.courierId(), p.amount(), "PAYMENT");
            outcome = "PAID";
        }
        publish(event, outcome, "PAYOUT");
    }

    private CreditReservation lockReservation(ErrandSettlementEvent event, String type) {
        var p = event.payload();
        // Serialize terminal decisions before locking the affected accounts.
        var reservation = reservations
                .lockByErrandId(p.errandId())
                .orElseThrow(() -> new IllegalStateException("Reservation is not ready; retry settlement"));
        boolean sameReservation = reservation.getOperationId().equals(p.reservationOperationId());
        var previous = transactions.findById(p.operationId());
        if (previous.isPresent()) {
            var log = previous.get();
            if (!sameReservation
                    || !log.getErrandId().equals(p.errandId())
                    || !log.getUserId().equals(p.requesterId())
                    || log.getAmount() != p.amount()
                    || !Objects.equals(log.getCourierId(), p.courierId())
                    || !log.getTransactionType().equals(type)) {
                throw new IllegalArgumentException("Settlement operation ID cannot be reused with changed data");
            }
        }
        return reservation;
    }

    private String existingOutcome(CreditReservation reservation, ErrandSettlementEvent.Payload p, String target) {
        if (!reservation.getOperationId().equals(p.reservationOperationId())
                || !reservation.getUserId().equals(p.requesterId())
                || reservation.getAmount() != p.amount()) {
            return "RESERVATION_MISMATCH";
        }
        if ("INSUFFICIENT_BALANCE".equals(reservation.getState())) {
            return "NO_RESERVATION";
        }
        if (!"RESERVED".equals(reservation.getState())) {
            boolean sameEffect =
                    target.equals(reservation.getState()) && Objects.equals(reservation.getCourierId(), p.courierId());
            return sameEffect ? target : "TERMINAL_CONFLICT";
        }
        return null;
    }

    private void publish(ErrandSettlementEvent event, String outcome, String settlementType) {
        var p = event.payload();
        events.publishEvent(new CreditSettlementResultEvent(
                UUID.randomUUID(),
                "CreditSettlementResultEvent",
                1,
                event.occurredAt(),
                new CreditSettlementResultEvent.Payload(
                        p.operationId(),
                        p.errandId(),
                        p.requesterId(),
                        p.amount(),
                        outcome,
                        settlementType,
                        p.courierId())));
    }
}
