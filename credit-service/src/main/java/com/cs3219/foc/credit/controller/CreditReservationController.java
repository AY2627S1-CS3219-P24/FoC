package com.cs3219.foc.credit.controller;

import com.cs3219.foc.credit.model.dto.CreditReservationResponse;
import com.cs3219.foc.credit.model.dto.ReserveCreditRequest;
import com.cs3219.foc.credit.service.CreditReservationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/credits/reservations")
@RequiredArgsConstructor
public class CreditReservationController {
    private final CreditReservationService service;

    @PostMapping
    public CreditReservationResponse reserve(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReserveCreditRequest request) {
        UUID user;
        try {
            user = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token subject must be a user UUID");
        }
        return service.reserve(user, request);
    }
}
