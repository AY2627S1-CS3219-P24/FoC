package com.cs3219.foc.user.service;

import com.cs3219.foc.user.exception.EmailVerificationException;
import com.cs3219.foc.user.exception.InvalidPasswordException;
import com.cs3219.foc.user.exception.UserNotFoundException;
import com.cs3219.foc.user.model.dto.ChangePasswordRequest;
import com.cs3219.foc.user.model.entity.EmailToken;
import com.cs3219.foc.user.model.entity.EmailTokenPurpose;
import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.repository.EmailTokenRepository;
import com.cs3219.foc.user.repository.RefreshTokenRepository;
import com.cs3219.foc.user.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PasswordService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailTokenService emailTokens;
    private final EmailTokenRepository emailTokenRepository;

    @Transactional
    public void requestPasswordReset(String email) {
        userRepository
                .findForUpdateByEmail(email)
                .filter(User::isEmailVerified)
                .ifPresent(user -> emailTokens.issue(user, user.getEmail(), EmailTokenPurpose.PASSWORD_RESET));
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        // consume holds the user lock; deleting the token and saving the password commit together.
        EmailToken stored;
        try {
            stored = emailTokens.consume(token, EmailTokenPurpose.PASSWORD_RESET);
        } catch (EmailVerificationException exception) {
            if (exception.getStatus() != HttpStatus.BAD_REQUEST) {
                throw exception;
            }
            throw new EmailVerificationException(HttpStatus.GONE, "This password reset link is no longer valid.");
        }
        var user = userRepository.findById(stored.getUserId()).orElseThrow();
        if (!stored.getEmail().equals(user.getEmail())) {
            throw new EmailVerificationException(HttpStatus.GONE, "This password reset link is no longer valid.");
        }
        savePassword(user, newPassword);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        var user =
                userRepository.findForUpdateById(userId).orElseThrow(() -> new UserNotFoundException("User not found"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidPasswordException("currentPassword", "Current password is incorrect");
        }
        if (request.currentPassword().equals(request.newPassword())) {
            throw new InvalidPasswordException("newPassword", "New password must differ from the current password");
        }
        savePassword(user, request.newPassword());
    }

    private void savePassword(User user, String newPassword) {
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.saveAndFlush(user);
        refreshTokenRepository.deleteAllByUserId(user.getId());
        emailTokenRepository.deleteAllByUserIdAndPurposeIn(
                user.getId(), List.of(EmailTokenPurpose.PASSWORD_RESET, EmailTokenPurpose.EMAIL_CHANGE));
    }
}
