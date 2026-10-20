package com.cs3219.foc.credit.exception;

import com.cs3219.foc.credit.model.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ReservationConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleReservationConflict(ReservationConflictException exception) {
        return new ErrorResponse("RESERVATION_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(CreditAccountPendingException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handlePendingAccount(CreditAccountPendingException exception) {
        return new ErrorResponse("CREDIT_ACCOUNT_PENDING", exception.getMessage());
    }
}
