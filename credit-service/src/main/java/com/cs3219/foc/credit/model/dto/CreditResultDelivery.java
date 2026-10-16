package com.cs3219.foc.credit.model.dto;

import com.cs3219.foc.credit.model.dto.event.CreditResultEvent;
import java.util.UUID;

public record CreditResultDelivery(CreditResultEvent event, UUID leaseToken, int attemptCount) {}
