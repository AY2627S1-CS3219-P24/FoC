package com.cs3219.foc.credit.repository;

import com.cs3219.foc.credit.model.entity.CreditSettlementInbox;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CreditSettlementInboxRepository extends JpaRepository<CreditSettlementInbox, UUID> {
    @Modifying
    @Query(
            value =
                    "INSERT INTO credit_settlement_inbox(event_id, operation_id) VALUES (:event, :operation) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int accept(@Param("event") UUID event, @Param("operation") UUID operation);

    @Query(
            value =
                    "SELECT * FROM credit_settlement_inbox WHERE processed_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP ORDER BY next_attempt_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<CreditSettlementInbox> lockNextPending();

    @Modifying
    @Query(
            value = "UPDATE credit_settlement_inbox SET processed_at = CURRENT_TIMESTAMP WHERE event_id = :id",
            nativeQuery = true)
    int complete(@Param("id") UUID id);

    @Modifying
    @Query(
            value =
                    "UPDATE credit_settlement_inbox SET next_attempt_at = CURRENT_TIMESTAMP + (:delay * INTERVAL '1 millisecond') WHERE event_id = :id",
            nativeQuery = true)
    int retryLater(@Param("id") UUID id, @Param("delay") long delay);
}
