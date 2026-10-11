package com.cs3219.foc.user.service;

import com.cs3219.foc.user.config.AccountMailProperties;
import com.cs3219.foc.user.exception.EmailVerificationException;
import com.cs3219.foc.user.model.dto.event.EmailTokenCreatedEvent;
import com.cs3219.foc.user.model.entity.EmailToken;
import com.cs3219.foc.user.model.entity.EmailTokenPurpose;
import com.cs3219.foc.user.model.entity.User;
import com.cs3219.foc.user.repository.EmailTokenRepository;
import com.cs3219.foc.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class EmailTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final EmailTokenRepository tokens;
    private final UserRepository users;
    private final AccountMailProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    // The caller has either just created the user or locked it before issuing a link.
    public void issue(User user, String email, EmailTokenPurpose purpose) {
        var now = OffsetDateTime.now(clock);
        var stored = tokens.findByUserIdAndPurpose(user.getId(), purpose).orElseGet(EmailToken::new);
        if (stored.getIssuedAt() != null
                && stored.getIssuedAt().plus(properties.resendInterval()).isAfter(now)) {
            // Public reset requests keep the same response without issuing another link.
            if (purpose == EmailTokenPurpose.PASSWORD_RESET) {
                return;
            }
            throw new EmailVerificationException(
                    HttpStatus.TOO_MANY_REQUESTS, "Please wait a minute before requesting another email.");
        }

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        stored.setUserId(user.getId());
        stored.setEmail(email);
        stored.setPurpose(purpose);
        stored.setTokenHash(hash(token));
        stored.setIssuedAt(now);
        stored.setExpiresAt(now.plus(properties.tokenTtl()));
        tokens.save(stored);
        events.publishEvent(new EmailTokenCreatedEvent(user.getId(), email, purpose, token));
    }

    public EmailToken consume(String token, EmailTokenPurpose purpose) {
        var digest = hash(token);
        var userId = tokens.findUserIdByTokenHash(digest).orElseThrow(EmailTokenService::invalidLink);
        // Serialize confirmation and resending on the account, then re-read the token.
        users.findForUpdateById(userId).orElseThrow(EmailTokenService::invalidLink);
        var stored = tokens.findByTokenHash(digest).orElseThrow(EmailTokenService::invalidLink);
        if (stored.getPurpose() != purpose || !stored.getExpiresAt().isAfter(OffsetDateTime.now(clock))) {
            throw invalidLink();
        }

        tokens.delete(stored);
        return stored;
    }

    private static EmailVerificationException invalidLink() {
        return new EmailVerificationException(HttpStatus.BAD_REQUEST, "This verification link is no longer valid.");
    }

    private static String hash(String token) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
