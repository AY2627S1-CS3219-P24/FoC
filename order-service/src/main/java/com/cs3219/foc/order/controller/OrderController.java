package com.cs3219.foc.order.controller;

import com.cs3219.foc.order.model.dto.*;
import com.cs3219.foc.order.service.OrderService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<CreateErrandResponse> create(@Valid @RequestBody CreateErrandRequest request) {
        CreateErrandResponse result = orderService.create(request);
        return ResponseEntity.ok().location(URI.create(result.queryUrl())).body(result);
    }

    @GetMapping
    public PageDto<ErrandDto> list(
            @RequestParam(required = false) UUID requesterId,
            @PageableDefault(
                            size = 20,
                            sort = {"createdAt", "id"},
                            direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return orderService.list(requesterId, pageable);
    }

    @GetMapping("/mine")
    public PageDto<ErrandDto> mine(
            @RequestParam(defaultValue = "requester") String as,
            @PageableDefault(
                            size = 20,
                            sort = {"createdAt", "id"},
                            direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return orderService.mine(as, pageable);
    }

    @GetMapping("/{id}")
    public ErrandDto get(@PathVariable UUID id) {
        return orderService.get(id);
    }

    @PostMapping("/{id}/accept")
    public ErrandActionResponse accept(@PathVariable UUID id) {
        return orderService.accept(id);
    }

    @PostMapping("/{id}/pick-up")
    public ErrandActionResponse pickUp(@PathVariable UUID id) {
        return orderService.pickUp(id);
    }

    @PostMapping("/{id}/complete")
    public ErrandActionResponse complete(@PathVariable UUID id) {
        return orderService.complete(id);
    }

    @PostMapping("/{id}/cancel")
    public ErrandActionResponse cancel(@PathVariable UUID id) {
        return orderService.cancel(id);
    }
}
