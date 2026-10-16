package com.cs3219.foc.order.exception;

import com.cs3219.foc.order.model.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleIdempotencyConflict(IdempotencyConflictException exception) {
        return new ErrorResponse("IDEMPOTENCY_KEY_CONFLICT", exception.getMessage());
    }
}
