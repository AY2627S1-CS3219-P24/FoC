package com.cs3219.foc.credit.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "credit_result_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditResultOutbox {
    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "errand_id")
    private UUID errandId;

    @Column(name = "requester_id")
    private UUID requesterId;

    @Column(name = "amount")
    private long amount;

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "attempt_count")
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "last_error")
    private String lastError;
}
