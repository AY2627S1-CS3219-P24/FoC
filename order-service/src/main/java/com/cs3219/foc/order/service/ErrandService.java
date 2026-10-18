package com.cs3219.foc.order.service;

import com.cs3219.foc.order.exception.IdempotencyConflictException;
import com.cs3219.foc.order.infrastructure.credit.CreditReservationClient;
import com.cs3219.foc.order.model.dto.*;
import com.cs3219.foc.order.model.entity.Errand;
import com.cs3219.foc.order.repository.ErrandRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ErrandService {
    private final ErrandRepository errands;
    private final CreditReservationClient credit;
    private final TransactionTemplate transactions;

    public ErrandService(ErrandRepository errands, CreditReservationClient credit, PlatformTransactionManager manager) {
        this.errands = errands;
        this.credit = credit;
        this.transactions = new TransactionTemplate(manager);
    }

    public ErrandResponse create(UUID requester, String key, CreateErrandRequest request, String token) {
        // Commit the request identity before HTTP so a lost response retries the same reservation.
        var errand = transactions.execute(status -> {
            errands.create(
                    UUID.randomUUID(),
                    requester,
                    key,
                    UUID.randomUUID(),
                    request.pickup(),
                    request.destination(),
                    request.description(),
                    request.reward());
            var stored =
                    errands.findByRequesterIdAndIdempotencyKey(requester, key).orElseThrow();
            if (!stored.getPickup().equals(request.pickup())
                    || !stored.getDestination().equals(request.destination())
                    || !stored.getDescription().equals(request.description())
                    || stored.getReward() != request.reward()) {
                throw new IdempotencyConflictException();
            }
            return stored;
        });
        if (errand.getCreditOutcome() != null) return response(errand);
        var decision = credit.reserve(
                new CreditReservationRequest(errand.getReservationOperationId(), errand.getId(), errand.getReward()),
                token);
        return transactions.execute(status -> {
            errands.recordFunding(
                    errand.getId(),
                    decision.outcome(),
                    "RESERVED".equals(decision.outcome()) ? "OPEN" : "CREDIT_REJECTED");
            return response(errands.findById(errand.getId()).orElseThrow());
        });
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
