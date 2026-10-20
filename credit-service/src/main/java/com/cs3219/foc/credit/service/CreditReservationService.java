package com.cs3219.foc.credit.service;

import static com.cs3219.foc.credit.model.entity.ReservationState.*;

import com.cs3219.foc.credit.exception.CreditAccountPendingException;
import com.cs3219.foc.credit.exception.ReservationConflictException;
import com.cs3219.foc.credit.model.dto.CreditReservationResponse;
import com.cs3219.foc.credit.model.dto.ReserveCreditRequest;
import com.cs3219.foc.credit.model.dto.event.CreditReservationResultEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandCreationStartedEvent;
import com.cs3219.foc.credit.model.entity.CreditReservation;
import com.cs3219.foc.credit.model.entity.ReservationState;
import com.cs3219.foc.credit.repository.*;
import com.cs3219.foc.credit.security.CurrentUserService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditReservationService {
    private final CreditAccountRepository creditAccountRepository;
    private final CreditReservationRepository creditReservationRepository;
    private final CreditTransactionLogRepository creditTransactionLogRepository;
    private final CurrentUserService currentUserService;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Transactional
    public CreditReservationResponse reserve(UUID user, ReserveCreditRequest request) {
        // All reservations for an account serialize before reading its available balance.
        creditAccountRepository.lockByUserId(user).orElseThrow(CreditAccountPendingException::new);
        return reserveLocked(user, request, null);
    }

    /** Message entry point has an explicit actor and never reads the HTTP security context. */
    @Transactional
    public void reserveForCreation(ErrandCreationStartedEvent event) {
        var payload = event.payload();
        var request = new ReserveCreditRequest(event.eventId(), payload.errandId(), payload.amount());
        // Order admits only ready accounts. Missing accounts here are processing failures, not a creation timeout.
        creditAccountRepository.lockByUserId(payload.requesterId()).orElseThrow(CreditAccountPendingException::new);
        var result = reserveLocked(payload.requesterId(), request, payload.reserveBefore());
        // Replays must also publish the original decision, even when no new money movement occurred.
        events.publishEvent(new CreditReservationResultEvent(
                UUID.randomUUID(),
                "CreditReservationResultEvent",
                1,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                new CreditReservationResultEvent.Payload(
                        event.eventId(),
                        payload.errandId(),
                        payload.requesterId(),
                        payload.amount(),
                        result.outcome())));
    }

    private CreditReservationResponse reserveLocked(
            UUID user, ReserveCreditRequest request, OffsetDateTime reserveBefore) {
        var existing = creditReservationRepository.findById(request.errandId());
        if (existing.isPresent()) return response(existing.get(), user, request);
        long available =
                creditAccountRepository.findBalanceByUserId(user).orElseThrow().availableBalance();
        ReservationState state = available >= request.amount() ? RESERVED : INSUFFICIENT_BALANCE;
        // This is the admission point under the account lock; a successful transaction may commit later.
        if (reserveBefore != null && !clock.instant().isBefore(reserveBefore.toInstant())) {
            return new CreditReservationResponse("CREATION_TIMEOUT");
        }
        int inserted = creditReservationRepository.reserve(
                request.errandId(), request.operationId(), user, request.amount(), state);
        var reservation =
                creditReservationRepository.findById(request.errandId()).orElseThrow(ReservationConflictException::new);
        var result = response(reservation, user, request);
        if (inserted == 1 && state == RESERVED) {
            creditTransactionLogRepository.record(
                    request.operationId(), request.errandId(), user, null, request.amount(), "RESERVE");
        }
        return result;
    }

    @Transactional
    public CreditReservationResponse reserve(ReserveCreditRequest request) {
        var userId = currentUserService.requireCurrentUserId();
        return reserve(userId, request);
    }

    private CreditReservationResponse response(CreditReservation stored, UUID user, ReserveCreditRequest request) {
        if (!stored.getOperationId().equals(request.operationId())
                || !stored.getUserId().equals(user)
                || stored.getAmount() != request.amount()) throw new ReservationConflictException();
        // A repeat reservation retains its original decision even after refund or payment.
        return new CreditReservationResponse(
                (stored.getState() == INSUFFICIENT_BALANCE ? INSUFFICIENT_BALANCE : RESERVED).name());
    }
}
