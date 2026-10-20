package com.cs3219.foc.credit.infrastructure.messaging.producer;

import com.cs3219.foc.credit.config.EventPublicationProperties;
import com.cs3219.foc.credit.model.dto.event.CreditReservationResultEvent;
import com.cs3219.foc.credit.model.dto.event.CreditSettlementResultEvent;
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
    public static final String SETTLEMENT_BINDING = "creditSettlementResult-out-0";
    public static final String RESERVATION_BINDING = "creditReservationResult-out-0";
    private final StreamBridge streamBridge;
    private final EventPublicationProperties properties;

    @ApplicationModuleListener(id = "credit-settlement-rabbit")
    public void publish(CreditSettlementResultEvent event) {
        send(SETTLEMENT_BINDING, event, event.eventId());
    }

    @ApplicationModuleListener(id = "credit-reservation-rabbit")
    public void publish(CreditReservationResultEvent event) {
        send(RESERVATION_BINDING, event, event.eventId());
    }

    private void send(String binding, Object event, UUID eventId) {
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
