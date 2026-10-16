package com.cs3219.foc.order.service;

import com.cs3219.foc.order.exception.IdempotencyConflictException;
import com.cs3219.foc.order.model.dto.CreateErrandRequest;
import com.cs3219.foc.order.model.dto.ErrandResponse;
import com.cs3219.foc.order.model.entity.Errand;
import com.cs3219.foc.order.repository.ErrandRepository;
import com.cs3219.foc.order.repository.ErrandSubmissionOutboxRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ErrandService {
    private final ErrandRepository errands;
    private final ErrandSubmissionOutboxRepository outbox;

    @Transactional
    public ErrandResponse create(UUID requester, String key, CreateErrandRequest request) {
        var id = UUID.randomUUID();
        int inserted = errands.create(
                id,
                requester,
                key,
                UUID.randomUUID(),
                request.pickup(),
                request.destination(),
                request.description(),
                request.reward());
        var errand = errands.findByRequesterIdAndIdempotencyKey(requester, key).orElseThrow();
        if (!errand.getPickup().equals(request.pickup())
                || !errand.getDestination().equals(request.destination())
                || !errand.getDescription().equals(request.description())
                || errand.getReward() != request.reward()) {
            throw new IdempotencyConflictException();
        }
        if (inserted == 1) outbox.enqueue(UUID.randomUUID(), id);
        return response(errand);
    }

    @Transactional(readOnly = true)
    public ErrandResponse get(UUID requester, UUID id) {
        return response(errands.findByIdAndRequesterId(id, requester)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Errand not found")));
    }

    private ErrandResponse response(Errand e) {
        return new ErrandResponse(
                e.getId(),
                e.getRequesterId(),
                e.getPickup(),
                e.getDestination(),
                e.getDescription(),
                e.getReward(),
                e.getStatus(),
                e.getCreditOutcome(),
                e.getCreatedAt());
    }
}
