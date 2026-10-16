package com.cs3219.foc.credit.infrastructure.messaging.producer;

import com.cs3219.foc.credit.config.CreditRewardProperties;
import com.cs3219.foc.credit.model.dto.event.CreditReservationResultEvent;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreditResultEventPublisher {
    public static final String BINDING = "creditResult-out-0";
    private final StreamBridge streamBridge;
    private final CreditRewardProperties properties;

    public void publish(CreditReservationResultEvent event) {
        // Correlation is per attempt; the business event ID remains unchanged on every retry.
        var correlation = new CorrelationData();
        var message = MessageBuilder.withPayload(event)
                .setHeader(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION, correlation)
                .setHeader("eventId", event.eventId().toString())
                .build();
        if (!streamBridge.send(BINDING, message)) {
            throw new IllegalStateException("Credit result message was not accepted for publication");
        }
        try {
            var confirm =
                    correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw new IllegalStateException("Credit result message was rejected or unroutable");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Credit result publication interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Credit result publication was not confirmed", exception);
        }
    }
}
