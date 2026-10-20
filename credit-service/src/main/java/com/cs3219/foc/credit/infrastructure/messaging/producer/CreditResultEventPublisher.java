package com.cs3219.foc.credit.infrastructure.messaging.producer;

import com.cs3219.foc.credit.config.EventPublicationProperties;
import com.cs3219.foc.credit.model.dto.event.CreditPaidEvent;
import com.cs3219.foc.credit.model.dto.event.CreditRefundedEvent;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreditResultEventPublisher {
    public static final String REFUND_BINDING = "creditRefunded-out-0";
    public static final String PAYOUT_BINDING = "creditPaid-out-0";
    private final StreamBridge streamBridge;
    private final EventPublicationProperties properties;

    @ApplicationModuleListener(id = "credit-refunded-rabbit")
    public void publish(CreditRefundedEvent event) {
        send(REFUND_BINDING, event.eventId(), event);
    }

    @ApplicationModuleListener(id = "credit-paid-rabbit")
    public void publish(CreditPaidEvent event) {
        send(PAYOUT_BINDING, event.eventId(), event);
    }

    private void send(String binding, UUID eventId, Object event) {
        // Correlation is per attempt; the business event ID remains unchanged on every retry.
        var correlation = new CorrelationData();
        var message = MessageBuilder.withPayload(event)
                .setHeader(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION, correlation)
                .setHeader("eventId", eventId.toString())
                .build();
        if (!streamBridge.send(binding, message)) {
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
