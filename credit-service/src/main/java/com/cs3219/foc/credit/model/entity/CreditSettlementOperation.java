package com.cs3219.foc.credit.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "credit_settlement_operations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditSettlementOperation {
    @Id
    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "reservation_operation_id")
    private UUID reservationOperationId;

    @Column(name = "errand_id")
    private UUID errandId;

    @Column(name = "requester_id")
    private UUID requesterId;

    @Column(name = "courier_id")
    private UUID courierId;

    @Column(name = "amount")
    private long amount;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
