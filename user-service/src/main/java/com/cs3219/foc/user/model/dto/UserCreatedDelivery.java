package com.cs3219.foc.user.model.dto;

import com.cs3219.foc.user.model.dto.event.UserCreatedEvent;
import java.util.UUID;

public record UserCreatedDelivery(UserCreatedEvent event, UUID leaseToken, int attemptCount) {}
