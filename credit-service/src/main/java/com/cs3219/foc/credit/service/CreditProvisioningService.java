package com.cs3219.foc.credit.service;

import com.cs3219.foc.credit.model.dto.event.UserCreatedEvent;
import com.cs3219.foc.credit.repository.CreditProvisioningInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditProvisioningService {
    private final CreditProvisioningInboxRepository inbox;
    private final CreditAccountService accounts;

    /** The consumer returns only after this transaction commits, allowing broker acknowledgement. */
    @Transactional
    public void accept(UserCreatedEvent event) {
        if (event == null
                || event.eventId() == null
                || event.occurredAt() == null
                || event.payload() == null
                || event.payload().userId() == null
                || event.schemaVersion() != 1
                || !"UserCreatedEvent".equals(event.eventType())) {
            throw new IllegalArgumentException("Unsupported or incomplete user-created event");
        }
        if (inbox.accept(event.eventId(), event.payload().userId(), event.occurredAt()) == 0) {
            var existing = inbox.findById(event.eventId()).orElseThrow();
            if (!existing.getUserId().equals(event.payload().userId())) {
                throw new IllegalArgumentException("User-created event ID belongs to a different user");
            }
        }
    }

    /** All work is local SQL; a row lock avoids leases and is released on commit or rollback. */
    @Transactional
    public boolean processNext() {
        var pending = inbox.lockNextPending();
        if (pending.isEmpty()) return false;
        var event = pending.get();
        accounts.initializeAccount(event.getUserId());
        inbox.markProcessed(event.getEventId());
        return true;
    }
}
