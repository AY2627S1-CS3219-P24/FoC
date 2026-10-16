package com.cs3219.foc.credit.model.dto;

import com.cs3219.foc.credit.model.dto.event.CreditReservationResultEvent;
import java.util.UUID;

public record CreditResultDelivery(CreditReservationResultEvent event, UUID leaseToken, int attemptCount) {}
