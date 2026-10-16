package com.cs3219.foc.credit.repository;

import com.cs3219.foc.credit.model.entity.CreditReservation;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CreditReservationRepository extends JpaRepository<CreditReservation, UUID> {
    @Modifying
    @Query(
            value =
                    "INSERT INTO credit_reservations(errand_id, operation_id, user_id, amount) VALUES (:errand, :operation, :user, :amount)",
            nativeQuery = true)
    int reserve(
            @Param("errand") UUID errand,
            @Param("operation") UUID operation,
            @Param("user") UUID user,
            @Param("amount") long amount);
}
