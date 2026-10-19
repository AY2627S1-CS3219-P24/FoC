package com.cs3219.foc.credit.repository;

import com.cs3219.foc.credit.model.entity.CreditAccount;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditAccountRepository extends JpaRepository<CreditAccount, UUID> {
    @Modifying
    @Query(value = """
                    INSERT INTO credit_accounts (user_id, total_balance)
                    VALUES (:userId, :initialAllocation)
                    ON CONFLICT (user_id) DO NOTHING
                    """, nativeQuery = true)
    int initializeIfAbsent(@Param("userId") UUID userId, @Param("initialAllocation") long initialAllocation);
}
