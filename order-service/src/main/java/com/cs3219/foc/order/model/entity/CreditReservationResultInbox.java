package com.cs3219.foc.order.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "credit_reservation_result_inbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditReservationResultInbox {
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

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;
}
