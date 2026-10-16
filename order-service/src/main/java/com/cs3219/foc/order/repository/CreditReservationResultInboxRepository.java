package com.cs3219.foc.order.repository;

import com.cs3219.foc.order.model.entity.CreditReservationResultInbox;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CreditReservationResultInboxRepository extends JpaRepository<CreditReservationResultInbox, UUID> {
    @Modifying
    @Query(value = """
        INSERT INTO credit_reservation_result_inbox(event_id,operation_id,errand_id,requester_id,amount,outcome)
        VALUES (:event,:operation,:errand,:requester,:amount,:outcome) ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int accept(
            @Param("event") UUID event,
            @Param("operation") UUID operation,
            @Param("errand") UUID errand,
            @Param("requester") UUID requester,
            @Param("amount") long amount,
            @Param("outcome") String outcome);

    @Query(value = """
        SELECT * FROM credit_reservation_result_inbox WHERE processed_at IS NULL
        ORDER BY received_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<CreditReservationResultInbox> lockNextPending();

    @Modifying
    @Query(
            value =
                    "UPDATE credit_reservation_result_inbox SET processed_at = CURRENT_TIMESTAMP WHERE event_id = :event",
            nativeQuery = true)
    int complete(@Param("event") UUID event);
}
