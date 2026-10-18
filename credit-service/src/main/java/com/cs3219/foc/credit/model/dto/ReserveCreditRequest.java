package com.cs3219.foc.credit.model.dto;

import com.cs3219.foc.credit.config.CreditAmountDeserializer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ReserveCreditRequest(
        @NotNull UUID operationId,
        @NotNull UUID errandId,

        @Positive @JsonDeserialize(using = CreditAmountDeserializer.class)
        long amount) {}
