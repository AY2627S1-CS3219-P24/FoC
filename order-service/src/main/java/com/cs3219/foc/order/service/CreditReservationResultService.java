package com.cs3219.foc.order.service;

import com.cs3219.foc.order.model.dto.event.CreditReservationResultEvent;
import com.cs3219.foc.order.repository.CreditReservationResultInboxRepository;
import com.cs3219.foc.order.repository.ErrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditReservationResultService {
    private final CreditReservationResultInboxRepository inbox;
    private final ErrandRepository errands;

    @Transactional
    public void accept(CreditReservationResultEvent event) {
        if (event == null
                || event.eventId() == null
                || event.occurredAt() == null
                || event.schemaVersion() != 1
                || !"CreditReservationResultEvent".equals(event.eventType())
                || event.payload() == null) {
            throw new IllegalArgumentException("Unsupported or incomplete credit reservation result");
        }
        var p = event.payload();
        if (p.operationId() == null
                || p.errandId() == null
                || p.requesterId() == null
                || p.amount() <= 0
                || !("RESERVED".equals(p.outcome()) || "INSUFFICIENT_BALANCE".equals(p.outcome()))) {
            throw new IllegalArgumentException("Credit reservation result requires IDs, amount and a final outcome");
        }
        var errand = errands.findById(p.errandId()).orElseThrow(() -> new IllegalArgumentException("Unknown errand"));
        if (!errand.getReservationOperationId().equals(p.operationId())
                || !errand.getRequesterId().equals(p.requesterId())
                || errand.getReward() != p.amount()) {
            throw new IllegalArgumentException("Credit result does not match the errand reservation");
        }
        if (inbox.accept(event.eventId(), p.operationId(), p.errandId(), p.requesterId(), p.amount(), p.outcome())
                == 0) {
            var existing = inbox.findById(event.eventId()).orElseThrow();
            if (!existing.getOperationId().equals(p.operationId())
                    || !existing.getOutcome().equals(p.outcome())) {
                throw new IllegalArgumentException("Credit result event ID cannot be reused with changed data");
            }
        }
    }

    @Transactional
    public boolean processNext() {
        var pending = inbox.lockNextPending();
        if (pending.isEmpty()) return false;
        var result = pending.get();
        var errand = errands.lockById(result.getErrandId()).orElseThrow();
        errands.recordFunding(
                errand.getId(),
                result.getOutcome(),
                "RESERVED".equals(result.getOutcome()) ? "OPEN" : "CREDIT_REJECTED");
        inbox.complete(result.getEventId());
        return true;
    }
}
