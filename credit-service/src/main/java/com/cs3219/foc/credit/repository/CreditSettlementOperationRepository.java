package com.cs3219.foc.credit.repository;

import com.cs3219.foc.credit.model.entity.CreditSettlementOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CreditSettlementOperationRepository extends JpaRepository<CreditSettlementOperation, UUID> {
    @Modifying
    @Query(
            value =
                    "INSERT INTO credit_settlement_operations(operation_id, reservation_operation_id, errand_id, requester_id, amount, courier_id, event_type) VALUES (:id, :reservation, :errand, :requester, :amount, :courier, :type) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int accept(
            @Param("id") UUID id,
            @Param("reservation") UUID reservation,
            @Param("errand") UUID errand,
            @Param("requester") UUID requester,
            @Param("amount") long amount,
            @Param("courier") UUID courier,
            @Param("type") String type);

    @Query(value = "SELECT * FROM credit_settlement_operations WHERE operation_id = :id FOR UPDATE", nativeQuery = true)
    Optional<CreditSettlementOperation> lockById(@Param("id") UUID id);

    @Modifying
    @Query(
            value =
                    "UPDATE credit_settlement_operations SET outcome = :outcome, completed_at = CURRENT_TIMESTAMP WHERE operation_id = :id",
            nativeQuery = true)
    int complete(@Param("id") UUID id, @Param("outcome") String outcome);
}
