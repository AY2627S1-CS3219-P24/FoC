package com.cs3219.foc.credit.service;

import com.cs3219.foc.credit.exception.CreditAccountPendingException;
import com.cs3219.foc.credit.model.dto.CreditBalanceResponse;
import com.cs3219.foc.credit.repository.CreditAccountRepository;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreditAccountService {
    private final CreditAccountRepository repository;

    /** Returns true only when this call creates the account and its initial grant. */
    @Transactional
    public boolean initializeAccount(UUID userId) {
        return repository.initializeIfAbsent(Objects.requireNonNull(userId, "userId")) == 1;
    }

    @Transactional(readOnly = true)
    public CreditBalanceResponse getBalance(UUID userId) {
        var account = repository.findById(userId).orElseThrow(CreditAccountPendingException::new);
        return new CreditBalanceResponse(account.getAvailableBalance(), account.getReservedBalance());
    }
}
