package com.cs3219.foc.credit.repository;

import com.cs3219.foc.credit.model.entity.CreditReserveOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CreditReserveOperationRepository extends JpaRepository<CreditReserveOperation, UUID> {
    @Modifying
    @Query(
            value =
                    "INSERT INTO credit_reserve_operations(operation_id, errand_id, requester_id, amount) VALUES (:id, :errand, :user, :amount) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int accept(
            @Param("id") UUID id, @Param("errand") UUID errand, @Param("user") UUID user, @Param("amount") long amount);

    @Query(value = "SELECT * FROM credit_reserve_operations WHERE operation_id = :id FOR UPDATE", nativeQuery = true)
    Optional<CreditReserveOperation> lockById(@Param("id") UUID id);

    @Modifying
    @Query(
            value =
                    "UPDATE credit_reserve_operations SET outcome = :outcome, completed_at = CURRENT_TIMESTAMP WHERE operation_id = :id",
            nativeQuery = true)
    int complete(@Param("id") UUID id, @Param("outcome") String outcome);
}
