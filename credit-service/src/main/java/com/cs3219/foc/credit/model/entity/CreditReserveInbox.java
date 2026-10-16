package com.cs3219.foc.credit.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "credit_reserve_inbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditReserveInbox {
    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;
}
