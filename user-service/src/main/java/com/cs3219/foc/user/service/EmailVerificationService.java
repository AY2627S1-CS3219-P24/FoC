package com.cs3219.foc.user.service;

import com.cs3219.foc.user.model.entity.EmailTokenPurpose;
import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class EmailVerificationService {
    private final UserRepository users;
    private final EmailTokenService tokens;

    public void createRegistrationVerification(User user) {
        tokens.issue(user, user.getEmail(), EmailTokenPurpose.REGISTRATION);
    }

    public void resendRegistrationVerification(String email) {
        users.findForUpdateByEmail(email)
                .filter(user -> !user.isEmailVerified())
                .ifPresent(this::createRegistrationVerification);
    }

    public void verifyEmail(String token) {
        var stored = tokens.consume(token, EmailTokenPurpose.REGISTRATION);
        var user = users.findById(stored.getUserId()).orElseThrow();
        user.setEmailVerified(true);
    }
}
