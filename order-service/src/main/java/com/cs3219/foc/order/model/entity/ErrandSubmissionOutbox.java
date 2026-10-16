package com.cs3219.foc.order.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "errand_submission_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ErrandSubmissionOutbox {
    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "errand_id")
    private UUID errandId;

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
