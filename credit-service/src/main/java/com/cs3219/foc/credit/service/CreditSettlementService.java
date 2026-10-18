package com.cs3219.foc.credit.service;

import com.cs3219.foc.credit.exception.CreditAccountPendingException;
import com.cs3219.foc.credit.model.dto.event.CreditSettlementResultEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandSettlementEvent;
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
    public void settle(ErrandSettlementEvent event) {
        var p = event.payload();
        // Serialize terminal decisions before locking the affected accounts.
        var reservation = reservations
                .lockByErrandId(p.errandId())
                .orElseThrow(() -> new IllegalStateException("Reservation is not ready; retry settlement"));
        boolean payout = "ErrandCompletedEvent".equals(event.eventType());
        String type = payout ? "PAYMENT" : "REFUND";
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
        if (!sameReservation
                || !reservation.getUserId().equals(p.requesterId())
                || reservation.getAmount() != p.amount()) {
            publish(event, "RESERVATION_MISMATCH");
            return;
        }
        if ("INSUFFICIENT_BALANCE".equals(reservation.getState())) {
            publish(event, "NO_RESERVATION");
            return;
        }
        String target = payout ? "PAID" : "REFUNDED";
        if (!"RESERVED".equals(reservation.getState())) {
            boolean sameEffect =
                    target.equals(reservation.getState()) && Objects.equals(reservation.getCourierId(), p.courierId());
            publish(event, sameEffect ? target : "TERMINAL_CONFLICT");
            return;
        }
        var recipient = payout ? p.courierId() : reservation.getUserId();
        // Use the same account order for transfers in either direction.
        for (var user : Stream.of(reservation.getUserId(), recipient)
                .distinct()
                .sorted()
                .toList()) {
            accounts.lockByUserId(user).orElseThrow(CreditAccountPendingException::new);
        }
        if (payout) {
            accounts.debitTotal(reservation.getUserId(), reservation.getAmount());
            accounts.creditTotal(recipient, reservation.getAmount());
        }
        reservations.settle(p.errandId(), target, p.courierId());
        transactions.record(p.operationId(), p.errandId(), p.requesterId(), p.courierId(), p.amount(), type);
        publish(event, target);
    }

    private void publish(ErrandSettlementEvent event, String outcome) {
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
                        "ErrandCompletedEvent".equals(event.eventType()) ? "PAYOUT" : "REFUND",
                        p.courierId())));
    }
}
