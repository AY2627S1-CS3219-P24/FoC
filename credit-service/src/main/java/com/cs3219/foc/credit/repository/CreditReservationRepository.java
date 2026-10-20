package com.cs3219.foc.credit.repository;

import com.cs3219.foc.credit.model.entity.CreditReservation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CreditReservationRepository extends JpaRepository<CreditReservation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CreditReservation r WHERE r.errandId = :id")
    Optional<CreditReservation> findForUpdateById(UUID id);
}
