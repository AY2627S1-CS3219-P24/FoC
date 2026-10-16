package com.cs3219.foc.credit.repository;

import com.cs3219.foc.credit.model.entity.CreditAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditAccountRepository extends JpaRepository<CreditAccount, UUID> {
    @Query(value = "SELECT * FROM credit_accounts WHERE user_id = :userId FOR UPDATE", nativeQuery = true)
    Optional<CreditAccount> lockByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query(
            value =
                    "UPDATE credit_accounts SET available_balance = available_balance - :amount, reserved_balance = reserved_balance + :amount, updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId",
            nativeQuery = true)
    int reserve(@Param("userId") UUID userId, @Param("amount") long amount);

    @Modifying
    @Query(
            value = "INSERT INTO credit_accounts (user_id) VALUES (:userId) ON CONFLICT (user_id) DO NOTHING",
            nativeQuery = true)
    int initializeIfAbsent(@Param("userId") UUID userId);
}
