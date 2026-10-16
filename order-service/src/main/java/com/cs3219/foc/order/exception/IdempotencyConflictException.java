package com.cs3219.foc.order.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency-Key has already been used with different errand details.");
    }
}
