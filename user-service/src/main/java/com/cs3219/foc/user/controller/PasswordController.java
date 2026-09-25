package com.cs3219.foc.user.controller;

import com.cs3219.foc.user.exception.InvalidPasswordException;
import com.cs3219.foc.user.model.dto.ChangePasswordRequest;
import com.cs3219.foc.user.model.dto.ValidationErrorResponse;
import com.cs3219.foc.user.service.AuthCookieFactory;
import com.cs3219.foc.user.service.PasswordService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me/password")
@RequiredArgsConstructor
public class PasswordController {
    private final PasswordService passwordService;
    private final AuthCookieFactory authCookieFactory;

    @PutMapping
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        passwordService.changePassword(UUID.fromString(jwt.getSubject()), request);
        return ResponseEntity.noContent()
                .header(
                        HttpHeaders.SET_COOKIE,
                        authCookieFactory.clearRefreshTokenCookie().toString())
                .build();
    }

    @ExceptionHandler(InvalidPasswordException.class)
    ResponseEntity<ValidationErrorResponse> handleInvalidPassword(InvalidPasswordException exception) {
        return ResponseEntity.badRequest()
                .body(new ValidationErrorResponse(
                        "Validation failed", Map.of(exception.getField(), exception.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ValidationErrorResponse> handleInvalidRequest(MethodArgumentNotValidException exception) {
        var errors = new TreeMap<String, String>();
        exception
                .getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ValidationErrorResponse("Validation failed", errors));
    }
}
