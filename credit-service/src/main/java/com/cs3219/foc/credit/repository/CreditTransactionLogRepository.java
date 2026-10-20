package com.cs3219.foc.credit.repository;

import com.cs3219.foc.credit.model.entity.CreditTransactionLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditTransactionLogRepository extends JpaRepository<CreditTransactionLog, UUID> {}
