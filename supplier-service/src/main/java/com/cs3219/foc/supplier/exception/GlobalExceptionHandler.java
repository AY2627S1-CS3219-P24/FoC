package com.cs3219.foc.supplier.exception;

import com.cs3219.foc.supplier.model.dto.ErrorResponse;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SupplierNotFoundException.class)
    ResponseEntity<ErrorResponse> handleSupplierNotFound(SupplierNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    /** when it is a bad query || path parameters. eg. an unknown category or id. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        var message = "Invalid value for " + ex.getName() + ": " + ex.getValue();
        // for the list parameters like ?category=A&category=B, look at element type
        var parameterType = ResolvableType.forMethodParameter(ex.getParameter());
        var type = parameterType.asCollection() == ResolvableType.NONE
                ? parameterType.resolve()
                : parameterType.asCollection().resolveGeneric(0);
        if (type != null && type.isEnum()) {
            message += " (expected one of " + Arrays.toString(type.getEnumConstants()) + ")";
        }
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleInvalidRequest(MethodArgumentNotValidException ex) {
        var message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }
}
