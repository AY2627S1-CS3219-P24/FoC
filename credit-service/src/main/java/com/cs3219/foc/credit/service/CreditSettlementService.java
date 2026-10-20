package com.cs3219.foc.credit.service;

import static com.cs3219.foc.credit.model.entity.ReservationState.*;

import com.cs3219.foc.credit.exception.CreditAccountPendingException;
import com.cs3219.foc.credit.exception.ReservationPendingException;
import com.cs3219.foc.credit.model.dto.event.CreditPaidEvent;
import com.cs3219.foc.credit.model.dto.event.CreditRefundedEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandCancelledEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandCompletedEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandExpiredEvent;
import com.cs3219.foc.credit.model.entity.CreditAccount;
import com.cs3219.foc.credit.model.entity.CreditReservation;
import com.cs3219.foc.credit.repository.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditSettlementService {
    private final CreditReservationRepository creditReservationRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void releaseReservedCredits(ErrandCancelledEvent event) {
        var payload = event.payload();
        releaseReservedCredits(
                event.eventId(), payload.errandId(), payload.requesterId(), payload.amount(), event.occurredAt());
    }

    @Transactional
    public void releaseReservedCredits(ErrandExpiredEvent event) {
        var payload = event.payload();
        releaseReservedCredits(
                event.eventId(), payload.errandId(), payload.requesterId(), payload.amount(), event.occurredAt());
    }

    private void releaseReservedCredits(
            UUID eventId, UUID errandId, UUID requesterId, long amount, OffsetDateTime occurredAt) {
        var reservation =
                creditReservationRepository.findForUpdateById(errandId).orElseThrow(ReservationPendingException::new);
        if (reservation.isRefunded()) {
            eventPublisher.publishEvent(CreditRefundedEvent.of(eventId, errandId, requesterId, amount, occurredAt));
            return;
        }
        if (!reservation.isReserved()) return;
        releaseReservation(reservation);
        eventPublisher.publishEvent(CreditRefundedEvent.of(eventId, errandId, requesterId, amount, occurredAt));
    }

    @Transactional
    public void transferReservedCredits(ErrandCompletedEvent event) {
        var payload = event.payload();
        var reservation = creditReservationRepository
                .findForUpdateById(payload.errandId())
                .orElseThrow(ReservationPendingException::new);

        if (reservation.isPaid()) {
            eventPublisher.publishEvent(CreditPaidEvent.of(
                    event.eventId(),
                    payload.errandId(),
                    payload.requesterId(),
                    payload.amount(),
                    payload.courierId(),
                    event.occurredAt()));
            return;
        }

        if (!reservation.isReserved()) return;

        var requesterAccount = creditAccountRepository
                .findForUpdateById(reservation.getUserId())
                .orElseThrow(CreditAccountPendingException::new);
        var courierAccount = creditAccountRepository
                .findForUpdateById(payload.courierId())
                .orElseThrow(CreditAccountPendingException::new);

        payCourier(requesterAccount, courierAccount, reservation);

        eventPublisher.publishEvent(CreditPaidEvent.of(
                event.eventId(),
                payload.errandId(),
                payload.requesterId(),
                payload.amount(),
                payload.courierId(),
                event.occurredAt()));
    }

    private void releaseReservation(CreditReservation reservation) {
        reservation.setState(REFUNDED);
        reservation.setSettledAt(OffsetDateTime.now());
    }

    private void payCourier(CreditAccount requester, CreditAccount courier, CreditReservation reservation) {
        requester.setTotalBalance(requester.getTotalBalance() - reservation.getAmount());
        courier.setTotalBalance(courier.getTotalBalance() + reservation.getAmount());
        reservation.setCourierId(courier.getUserId());
        reservation.setState(PAID);
        reservation.setSettledAt(OffsetDateTime.now());
    }
}
