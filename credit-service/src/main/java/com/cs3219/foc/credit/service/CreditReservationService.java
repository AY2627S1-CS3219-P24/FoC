package com.cs3219.foc.credit.service;

import static com.cs3219.foc.credit.model.entity.ReservationState.*;

import com.cs3219.foc.credit.exception.CreditAccountPendingException;
import com.cs3219.foc.credit.exception.ReservationConflictException;
import com.cs3219.foc.credit.model.dto.CreditReservationResponse;
import com.cs3219.foc.credit.model.dto.ReserveCreditRequest;
import com.cs3219.foc.credit.model.entity.CreditReservation;
import com.cs3219.foc.credit.repository.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditReservationService {
    private final CreditAccountRepository accounts;
    private final CreditReservationRepository reservations;
    private final CreditTransactionLogRepository transactions;

    @Transactional
    public CreditReservationResponse reserve(UUID user, ReserveCreditRequest request) {
        // All reservations for an account serialize before reading its available balance.
        accounts.lockByUserId(user).orElseThrow(CreditAccountPendingException::new);
        var existing = reservations.findById(request.errandId());
        if (existing.isPresent()) return response(existing.get(), user, request);
        var available = accounts.findBalanceByUserId(user).orElseThrow().availableBalance();
        var state = available >= request.amount() ? RESERVED : INSUFFICIENT_BALANCE;
        int inserted = reservations.reserve(request.errandId(), request.operationId(), user, request.amount(), state);
        var reservation = reservations.findById(request.errandId()).orElseThrow(ReservationConflictException::new);
        var result = response(reservation, user, request);
        if (inserted == 1 && state == RESERVED) {
            transactions.record(request.operationId(), request.errandId(), user, null, request.amount(), "RESERVE");
        }
        return result;
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
