package com.cs3219.foc.user.repository;

import com.cs3219.foc.user.model.entity.UserCreatedOutbox;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserCreatedOutboxRepository extends JpaRepository<UserCreatedOutbox, UUID> {
    // Flush the newly saved user before the native query; both writes remain in its transaction.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            WITH recorded AS (
                INSERT INTO user_credit_provisioning (user_id, event_id)
                SELECT id, gen_random_uuid() FROM users WHERE id = :userId
                ON CONFLICT (user_id) DO NOTHING
                RETURNING user_id, event_id
            )
            INSERT INTO user_created_outbox (event_id, user_id, occurred_at)
            SELECT r.event_id, r.user_id, u.created_at FROM recorded r JOIN users u ON u.id = r.user_id
            """, nativeQuery = true)
    int recordUser(@Param("userId") UUID userId);

    // The permanent receipt is the progress marker; no in-memory cursor or OFFSET can skip a new user.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            WITH recorded AS (
                INSERT INTO user_credit_provisioning (user_id, event_id)
                SELECT u.id, gen_random_uuid() FROM users u
                WHERE NOT EXISTS (SELECT 1 FROM user_credit_provisioning p WHERE p.user_id = u.id)
                ORDER BY u.id LIMIT :batchSize
                ON CONFLICT (user_id) DO NOTHING
                RETURNING user_id, event_id
            )
            INSERT INTO user_created_outbox (event_id, user_id, occurred_at)
            SELECT r.event_id, r.user_id, u.created_at FROM recorded r JOIN users u ON u.id = r.user_id
            """, nativeQuery = true)
    int backfill(@Param("batchSize") int batchSize);

    @Query(value = """
            SELECT * FROM user_created_outbox
            WHERE published_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP
              AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
            ORDER BY next_attempt_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<UserCreatedOutbox> lockNextPending();

    @Modifying
    @Query(value = """
            UPDATE user_created_outbox SET lease_token = :token,
              lease_until = CURRENT_TIMESTAMP + (:leaseMillis * INTERVAL '1 millisecond'),
              attempt_count = attempt_count + 1
            WHERE event_id = :eventId
            """, nativeQuery = true)
    int acquireLease(
            @Param("eventId") UUID eventId, @Param("token") UUID token, @Param("leaseMillis") long leaseMillis);

    @Modifying
    @Query(value = """
            UPDATE user_created_outbox SET published_at = CURRENT_TIMESTAMP,
              lease_token = NULL, lease_until = NULL, last_error = NULL
            WHERE event_id = :eventId AND lease_token = :token AND published_at IS NULL
            """, nativeQuery = true)
    int markPublished(@Param("eventId") UUID eventId, @Param("token") UUID token);

    @Modifying
    @Query(value = """
            UPDATE user_created_outbox SET next_attempt_at = CURRENT_TIMESTAMP + (:delayMillis * INTERVAL '1 millisecond'),
              lease_token = NULL, lease_until = NULL, last_error = :error
            WHERE event_id = :eventId AND lease_token = :token AND published_at IS NULL
            """, nativeQuery = true)
    int retryLater(
            @Param("eventId") UUID eventId,
            @Param("token") UUID token,
            @Param("delayMillis") long delayMillis,
            @Param("error") String error);
}
