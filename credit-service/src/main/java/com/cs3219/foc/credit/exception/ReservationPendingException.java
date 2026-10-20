package com.cs3219.foc.credit.exception;

public class ReservationPendingException extends RuntimeException {
    public ReservationPendingException() {
        super("Reservation is not ready. Please try again later.");
    }
}
