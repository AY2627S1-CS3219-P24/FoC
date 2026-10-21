package com.cs3219.foc.order.infrastructure.messaging.producer;

import com.cs3219.foc.order.config.EventPublicationProperties;
import com.cs3219.foc.order.model.dto.event.*;
import java.util.UUID;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ErrandEventPublisher {
    private final StreamBridge streamBridge;
    private final EventPublicationProperties properties;

    @ApplicationModuleListener(id = "order-completed-rabbit")
    public void publish(ErrandCompletedEvent event) {
        send("errandCompleted-out-0", event, event.eventId());
    }

    @ApplicationModuleListener(id = "order-cancelled-rabbit")
    public void publish(ErrandCancelledEvent event) {
        send("errandCancelled-out-0", event, event.eventId());
    }

    @ApplicationModuleListener(id = "order-expired-rabbit")
    public void publish(ErrandExpiredEvent event) {
        send("errandExpired-out-0", event, event.eventId());
    }

    private void send(String binding, Object event, UUID eventId) {
        var correlation = new CorrelationData();
        var message = MessageBuilder.withPayload(event)
                .setHeader(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION, correlation)
                .setHeader("eventId", eventId.toString())
                .build();
        if (!streamBridge.send(binding, message))
            throw new IllegalStateException("Order message was not accepted for publication");
        try {
            var confirm =
                    correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw new IllegalStateException("Order message was rejected or unroutable");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Order publication interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Order publication was not confirmed", exception);
        }
    }
}
