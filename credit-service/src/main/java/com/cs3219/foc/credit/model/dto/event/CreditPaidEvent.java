package com.cs3219.foc.credit.model.dto.event;

import java.util.UUID;

public record CreditPaidEvent(UUID operationId, UUID errandId, UUID requesterId, long amount, UUID courierId) {}
