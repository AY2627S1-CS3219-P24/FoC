package com.cs3219.foc.credit.service;

import static com.cs3219.foc.credit.model.entity.ReservationState.INSUFFICIENT_BALANCE;
import static com.cs3219.foc.credit.model.entity.ReservationState.RESERVED;

import com.cs3219.foc.credit.exception.CreditAccountPendingException;
import com.cs3219.foc.credit.exception.ReservationConflictException;
import com.cs3219.foc.credit.model.dto.CreditReservationResponse;
import com.cs3219.foc.credit.model.dto.ReserveCreditRequest;
import com.cs3219.foc.credit.model.dto.event.CreditReservedEvent;
import com.cs3219.foc.credit.model.entity.CreditReservation;
import com.cs3219.foc.credit.repository.CreditAccountRepository;
import com.cs3219.foc.credit.repository.CreditReservationRepository;
import com.cs3219.foc.credit.security.CurrentUserService;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditReservationService {
    private final CreditAccountRepository creditAccountRepository;
    private final CreditReservationRepository creditReservationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserService currentUserService;

    @Transactional
    public CreditReservationResponse reserve(UUID user, ReserveCreditRequest request) {
        // All reservations for an account serialize before reading its available balance.
        creditAccountRepository.findForUpdateById(user).orElseThrow(CreditAccountPendingException::new);
        var existingReservation =
                creditReservationRepository.findById(request.errandId()).orElse(null);

        if (existingReservation != null) {
            validateReservation(existingReservation, user, request);
            return buildReservationResponse(existingReservation);
        }

        var availableBalance = creditAccountRepository
                .findBalanceByUserId(user)
                .orElseThrow(CreditAccountPendingException::new)
                .availableBalance();

        var state = RESERVED;
        OffsetDateTime settledAt = null;
        if (availableBalance < request.amount()) {
            state = INSUFFICIENT_BALANCE;
            settledAt = OffsetDateTime.now();
        }
        var reservation = CreditReservation.builder()
                .errandId(request.errandId())
                .operationId(request.operationId())
                .userId(user)
                .amount(request.amount())
                .state(state)
                .settledAt(settledAt)
                .build();

        try {
            creditReservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException exception) {
            throw new ReservationConflictException();
        }

        if (reservation.isReserved()) {
            eventPublisher.publishEvent(
                    new CreditReservedEvent(request.operationId(), request.errandId(), user, request.amount()));
        }

        return buildReservationResponse(reservation);
    }

    @Transactional
    public CreditReservationResponse reserve(ReserveCreditRequest request) {
        var userId = currentUserService.requireCurrentUserId();
        return reserve(userId, request);
    }

    private void validateReservation(CreditReservation stored, UUID user, ReserveCreditRequest request) {
        if (!stored.getOperationId().equals(request.operationId())
                || !stored.getUserId().equals(user)
                || stored.getAmount() != request.amount()) throw new ReservationConflictException();
    }

    // A repeat reservation retains its original decision even after refund or payment.
    private CreditReservationResponse buildReservationResponse(CreditReservation stored) {
        if (stored.isInsufficientBalance()) {
            return new CreditReservationResponse(INSUFFICIENT_BALANCE);
        }
        return new CreditReservationResponse(RESERVED);
    }
}
