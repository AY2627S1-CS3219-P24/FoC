package com.cs3219.foc.credit.service;

import static com.cs3219.foc.credit.model.entity.CreditTransactionType.*;

import com.cs3219.foc.credit.model.dto.event.CreditPaidEvent;
import com.cs3219.foc.credit.model.dto.event.CreditRefundedEvent;
import com.cs3219.foc.credit.model.dto.event.CreditReservedEvent;
import com.cs3219.foc.credit.model.entity.CreditTransactionLog;
import com.cs3219.foc.credit.repository.CreditTransactionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class CreditTransactionLogListener {
    private final CreditTransactionLogRepository creditTransactionLogRepository;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void recordReservation(CreditReservedEvent event) {
        var log = CreditTransactionLog.builder()
                .operationId(event.operationId())
                .errandId(event.errandId())
                .userId(event.userId())
                .amount(event.amount())
                .transactionType(RESERVE)
                .build();
        creditTransactionLogRepository.save(log);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void recordRefund(CreditRefundedEvent event) {
        var payload = event.payload();
        var log = CreditTransactionLog.builder()
                .operationId(payload.operationId())
                .errandId(payload.errandId())
                .userId(payload.requesterId())
                .amount(payload.amount())
                .transactionType(REFUND)
                .build();
        creditTransactionLogRepository.save(log);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void recordPayment(CreditPaidEvent event) {
        var payload = event.payload();
        var log = CreditTransactionLog.builder()
                .operationId(payload.operationId())
                .errandId(payload.errandId())
                .userId(payload.requesterId())
                .courierId(payload.courierId())
                .amount(payload.amount())
                .transactionType(PAYMENT)
                .build();
        creditTransactionLogRepository.save(log);
    }
}
