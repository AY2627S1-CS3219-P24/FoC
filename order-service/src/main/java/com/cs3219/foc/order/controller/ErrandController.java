package com.cs3219.foc.order.controller;

import com.cs3219.foc.order.model.dto.CreateErrandRequest;
import com.cs3219.foc.order.model.dto.ErrandResponse;
import com.cs3219.foc.order.service.ErrandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class ErrandController {
    private final ErrandService service;

    @PostMapping
    public ResponseEntity<ErrandResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @Valid @RequestBody CreateErrandRequest request) {
        var errand = service.create(userId(jwt), key, request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(errand.id())
                .toUri();
        return ResponseEntity.status("PENDING_CREDIT".equals(errand.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(location)
                .body(errand);
    }

    @GetMapping("/{id}")
    public ErrandResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(userId(jwt), id);
    }

    private UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token subject must be a user UUID");
        }
    }
}
