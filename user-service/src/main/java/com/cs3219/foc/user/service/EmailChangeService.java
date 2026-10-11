package com.cs3219.foc.user.service;

import com.cs3219.foc.user.exception.EmailVerificationException;
import com.cs3219.foc.user.exception.UnsupportedEmailDomainException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.model.entity.EmailTokenPurpose;
import com.cs3219.foc.user.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class EmailChangeService {
    private final UserRepository users;
    private final EmailTokenService tokens;

    public void requestChange(UUID userId, String email) {
        var user = users.findForUpdateById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
        if (!NusEmailPolicy.isAllowed(email)) {
            throw new UnsupportedEmailDomainException();
        }
        if (user.getEmail().equals(email)) {
            throw new EmailVerificationException(HttpStatus.BAD_REQUEST, "Enter a different email address.");
        }
        requireAvailable(email);
        tokens.issue(user, email, EmailTokenPurpose.EMAIL_CHANGE);
    }

    public void confirmChange(String token) {
        var stored = tokens.consume(token, EmailTokenPurpose.EMAIL_CHANGE);
        var user = users.findById(stored.getUserId()).orElseThrow();
        user.setEmail(stored.getEmail());
        user.setEmailVerified(true);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            // The unique constraint also covers addresses claimed since the request was sent.
            if (exception.getCause() instanceof ConstraintViolationException constraint
                    && "users_email_key".equals(constraint.getConstraintName())) {
                throw emailInUse();
            }
            throw exception;
        }
    }

    private void requireAvailable(String email) {
        if (users.existsByEmail(email)) {
            throw emailInUse();
        }
    }

    private static EmailVerificationException emailInUse() {
        return new EmailVerificationException(HttpStatus.CONFLICT, "This email address is already in use.");
    }
}
