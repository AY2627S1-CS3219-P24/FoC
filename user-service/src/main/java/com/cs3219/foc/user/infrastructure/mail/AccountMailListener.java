package com.cs3219.foc.user.infrastructure.mail;

import com.cs3219.foc.user.config.AccountMailProperties;
import com.cs3219.foc.user.model.dto.event.EmailTokenCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountMailListener {
    private final JavaMailSender sender;
    private final AccountMailProperties properties;

    @TransactionalEventListener
    @Async
    public void sendVerification(EmailTokenCreatedEvent event) {
        var link = UriComponentsBuilder.fromUri(properties.frontendUrl())
                .replacePath("/verify-email")
                .queryParam("token", event.token())
                .build()
                .toUriString();
        var message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(event.email());
        message.setSubject("Verify your FoC email address");
        message.setText("""
                Welcome to FoC!

                Open this link to verify your email address:
                %s

                This link expires in %d minutes. If you requested another email, use the latest link.
                If you did not create this account, you can ignore this email.
                """.formatted(link, properties.tokenTtl().toMinutes()));
        try {
            sender.send(message);
        } catch (MailException exception) {
            // The account is already committed. Resending issues another link.
            log.warn(
                    "Verification email delivery failed for user {} ({})",
                    event.userId(),
                    exception.getClass().getSimpleName());
        }
    }
}
