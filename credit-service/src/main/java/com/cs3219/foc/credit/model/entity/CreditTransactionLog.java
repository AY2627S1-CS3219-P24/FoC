package com.cs3219.foc.credit.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "credit_transaction_log")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditTransactionLog {
    @Id
    @Column
    private UUID operationId;

    @Column
    private UUID errandId;

    @Column
    private UUID userId;

    @Column
    private UUID courierId;

    @Column
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column
    private CreditTransactionType transactionType;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
