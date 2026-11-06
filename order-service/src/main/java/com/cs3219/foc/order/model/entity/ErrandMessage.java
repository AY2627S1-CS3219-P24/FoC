package com.cs3219.foc.order.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One chat message between an errand's requester and courier. */
@Entity
@Table(name = "errand_messages")
@Getter
@Setter
@NoArgsConstructor
public class ErrandMessage {
    // Chosen by the sender's app, so a retried send is stored only once.
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "errand_id", nullable = false)
    private UUID errandId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(nullable = false, length = 1000)
    private String body;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;
}
