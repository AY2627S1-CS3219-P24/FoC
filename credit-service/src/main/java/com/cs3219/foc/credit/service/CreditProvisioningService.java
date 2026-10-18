package com.cs3219.foc.credit.service;

import com.cs3219.foc.credit.model.dto.event.UserCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CreditProvisioningService {
    private final CreditAccountService accounts;

    /** Account initialization commits before the consumer returns and acknowledges delivery. */
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
        accounts.initializeAccount(event.payload().userId());
    }
}
