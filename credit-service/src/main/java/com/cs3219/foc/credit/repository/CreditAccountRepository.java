package com.cs3219.foc.credit.repository;

import com.cs3219.foc.credit.model.dto.CreditBalanceResponse;
import com.cs3219.foc.credit.model.entity.CreditAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CreditAccountRepository extends JpaRepository<CreditAccount, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM CreditAccount a WHERE a.userId = :id")
    Optional<CreditAccount> findForUpdateById(UUID id);

    @Query("""
            SELECT new com.cs3219.foc.credit.model.dto.CreditBalanceResponse(
                a.totalBalance - COALESCE(SUM(r.amount), 0), COALESCE(SUM(r.amount), 0))
            FROM CreditAccount a
            LEFT JOIN CreditReservation r ON r.userId = a.userId
                AND r.state = com.cs3219.foc.credit.model.entity.ReservationState.RESERVED
            WHERE a.userId = :userId
            GROUP BY a.userId, a.totalBalance
            """)
    Optional<CreditBalanceResponse> findBalanceByUserId(UUID userId);

    @Modifying
    @Query(value = """
                    INSERT INTO credit_accounts (user_id, total_balance)
                    VALUES (:userId, :initialAllocation)
                    ON CONFLICT (user_id) DO NOTHING
                    """, nativeQuery = true)
    void initializeIfAbsent(UUID userId, long initialAllocation);
}
