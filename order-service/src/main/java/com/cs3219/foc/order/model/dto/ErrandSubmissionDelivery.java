package com.cs3219.foc.order.model.dto;

import com.cs3219.foc.order.model.dto.event.ErrandSubmittedEvent;
import java.util.UUID;

public record ErrandSubmissionDelivery(ErrandSubmittedEvent event, UUID leaseToken, int attemptCount) {}
