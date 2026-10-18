package com.cs3219.foc.order.model.dto;

import java.util.UUID;

public record CreditReservationRequest(UUID operationId, UUID errandId, long amount) {}
