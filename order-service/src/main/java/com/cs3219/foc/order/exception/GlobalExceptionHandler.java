package com.cs3219.foc.order.exception;

import com.cs3219.foc.order.model.dto.ErrorResponse;
import com.cs3219.foc.order.model.dto.ErrorResponseWithFieldErrors;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.Errors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CreditAccountPendingException.class)
    ResponseEntity<ErrorResponse> accountPending(CreditAccountPendingException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CREDIT_ACCOUNT_PENDING", e.getMessage()));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(OrderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("ORDER_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(OrderConflictException.class)
    ResponseEntity<ErrorResponse> conflict(OrderConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("ORDER_CONFLICT", e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("FORBIDDEN", "Access denied"));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorResponse> unauthenticated(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHENTICATED", "Authentication required"));
    }

    @ExceptionHandler(ValidationFailedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponseWithFieldErrors handleValidationFailed(ValidationFailedException exception) {
        var fieldErrors = extractFieldErrors(exception.getValidationErrors());

        return new ErrorResponseWithFieldErrors("VALIDATION_FAILED", exception.getMessage(), fieldErrors);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponseWithFieldErrors handleRequestValidation(MethodArgumentNotValidException exception) {
        var fieldErrors = extractFieldErrors(exception.getBindingResult());

        return new ErrorResponseWithFieldErrors("VALIDATION_FAILED", "Validation failed", fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponseWithFieldErrors handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return new ErrorResponseWithFieldErrors(
                "INVALID_REQUEST", "Request body is missing or contains invalid fields", Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponseWithFieldErrors handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return new ErrorResponseWithFieldErrors(
                "INVALID_REQUEST", "Invalid request fields", Map.of(exception.getName(), "Invalid value"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new ErrorResponse("ORDER_REQUEST_FAILED", e.getReason()));
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<ErrorResponse> upstream(RestClientException e) {
        log.error("Order upstream request failed", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("UPSTREAM_ERROR", "Unable to complete the service request"));
    }

    private static Map<String, String> extractFieldErrors(Errors errors) {
        var fieldErrors = new TreeMap<String, String>();
        errors.getFieldErrors().forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return fieldErrors;
    }
}
