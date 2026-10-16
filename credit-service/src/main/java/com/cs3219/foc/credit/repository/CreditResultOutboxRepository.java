package com.cs3219.foc.credit.repository;

import com.cs3219.foc.credit.model.entity.CreditResultOutbox;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditResultOutboxRepository extends JpaRepository<CreditResultOutbox, UUID> {
    @Modifying
    @Query(
            value =
                    "INSERT INTO credit_result_outbox(event_id, operation_id, errand_id, requester_id, amount, outcome) SELECT :event, operation_id, errand_id, requester_id, amount, outcome FROM credit_reserve_operations WHERE operation_id = :operation",
            nativeQuery = true)
    int enqueue(@Param("event") UUID event, @Param("operation") UUID operation);

    @Modifying
    @Query(
            value =
                    "UPDATE credit_result_outbox SET published_at = NULL, next_attempt_at = CURRENT_TIMESTAMP WHERE operation_id = :operation AND published_at IS NOT NULL",
            nativeQuery = true)
    int replay(@Param("operation") UUID operation);

    @Query(value = """
            SELECT * FROM credit_result_outbox
            WHERE published_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP
              AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
            ORDER BY next_attempt_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<CreditResultOutbox> lockNextPending();

    @Modifying
    @Query(value = """
            UPDATE credit_result_outbox SET lease_token = :token,
              lease_until = CURRENT_TIMESTAMP + (:leaseMillis * INTERVAL '1 millisecond'),
              attempt_count = attempt_count + 1
            WHERE event_id = :eventId
            """, nativeQuery = true)
    int acquireLease(
            @Param("eventId") UUID eventId, @Param("token") UUID token, @Param("leaseMillis") long leaseMillis);

    @Modifying
    @Query(value = """
            UPDATE credit_result_outbox SET published_at = CURRENT_TIMESTAMP,
              lease_token = NULL, lease_until = NULL, last_error = NULL
            WHERE event_id = :eventId AND lease_token = :token AND published_at IS NULL
            """, nativeQuery = true)
    int markPublished(@Param("eventId") UUID eventId, @Param("token") UUID token);

    @Modifying
    @Query(value = """
            UPDATE credit_result_outbox SET next_attempt_at = CURRENT_TIMESTAMP + (:delayMillis * INTERVAL '1 millisecond'),
              lease_token = NULL, lease_until = NULL, last_error = :error
            WHERE event_id = :eventId AND lease_token = :token AND published_at IS NULL
            """, nativeQuery = true)
    int retryLater(
            @Param("eventId") UUID eventId,
            @Param("token") UUID token,
            @Param("delayMillis") long delayMillis,
            @Param("error") String error);
}
