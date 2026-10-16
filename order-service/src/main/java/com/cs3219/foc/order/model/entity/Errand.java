package com.cs3219.foc.order.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "errands")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Errand {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "requester_id")
    private UUID requesterId;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "reservation_operation_id")
    private UUID reservationOperationId;

    @Column(name = "pickup")
    private String pickup;

    @Column(name = "destination")
    private String destination;

    @Column(name = "description")
    private String description;

    @Column(name = "reward")
    private long reward;

    @Column(name = "status")
    private String status;

    @Column(name = "credit_outcome")
    private String creditOutcome;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
