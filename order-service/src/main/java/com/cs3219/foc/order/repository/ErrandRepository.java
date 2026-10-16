package com.cs3219.foc.order.repository;

import com.cs3219.foc.order.model.entity.Errand;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ErrandRepository extends JpaRepository<Errand, UUID> {
    @Modifying
    @Query(value = """
        INSERT INTO errands(id,requester_id,idempotency_key,reservation_operation_id,pickup,destination,description,reward)
        VALUES (:id,:requester,:key,:operation,:pickup,:destination,:description,:reward)
        ON CONFLICT (requester_id,idempotency_key) DO NOTHING
        """, nativeQuery = true)
    int create(
            @Param("id") UUID id,
            @Param("requester") UUID requester,
            @Param("key") String key,
            @Param("operation") UUID operation,
            @Param("pickup") String pickup,
            @Param("destination") String destination,
            @Param("description") String description,
            @Param("reward") long reward);

    Optional<Errand> findByRequesterIdAndIdempotencyKey(UUID requesterId, String idempotencyKey);

    Optional<Errand> findByIdAndRequesterId(UUID id, UUID requesterId);

    @Query(value = "SELECT * FROM errands WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Errand> lockById(@Param("id") UUID id);

    @Modifying
    @Query(value = """
        UPDATE errands SET credit_outcome = :outcome,
          status = CASE WHEN status = 'PENDING_CREDIT' THEN :status ELSE status END,
          updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND credit_outcome IS NULL
        """, nativeQuery = true)
    int recordFunding(@Param("id") UUID id, @Param("outcome") String outcome, @Param("status") String status);
}
