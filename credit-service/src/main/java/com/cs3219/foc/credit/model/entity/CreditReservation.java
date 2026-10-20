package com.cs3219.foc.credit.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "credit_reservations")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditReservation {
    @Id
    @Column
    private UUID errandId;

    @Column
    private UUID operationId;

    @Column
    private UUID userId;

    @Column
    private long amount;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column
    private ReservationState state;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Setter
    @Column
    private UUID courierId;

    @Setter
    @Column
    private OffsetDateTime settledAt;

    public boolean isReserved() {
        return state == ReservationState.RESERVED;
    }

    public boolean isRefunded() {
        return state == ReservationState.REFUNDED;
    }

    public boolean isPaid() {
        return state == ReservationState.PAID;
    }

    public boolean isInsufficientBalance() {
        return state == ReservationState.INSUFFICIENT_BALANCE;
    }
}
