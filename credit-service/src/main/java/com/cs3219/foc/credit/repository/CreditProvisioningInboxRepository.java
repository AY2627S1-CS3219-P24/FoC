package com.cs3219.foc.credit.repository;

import com.cs3219.foc.credit.model.entity.CreditProvisioningInbox;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditProvisioningInboxRepository extends JpaRepository<CreditProvisioningInbox, UUID> {
    @Modifying
    @Query(value = """
            INSERT INTO credit_provisioning_inbox (event_id, user_id, occurred_at)
            VALUES (:eventId, :userId, :occurredAt) ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int accept(
            @Param("eventId") UUID eventId,
            @Param("userId") UUID userId,
            @Param("occurredAt") OffsetDateTime occurredAt);

    @Query(value = """
            SELECT * FROM credit_provisioning_inbox WHERE processed_at IS NULL
            ORDER BY received_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<CreditProvisioningInbox> lockNextPending();

    @Modifying
    @Query(
            value = "UPDATE credit_provisioning_inbox SET processed_at = CURRENT_TIMESTAMP WHERE event_id = :eventId",
            nativeQuery = true)
    void markProcessed(@Param("eventId") UUID eventId);
}
