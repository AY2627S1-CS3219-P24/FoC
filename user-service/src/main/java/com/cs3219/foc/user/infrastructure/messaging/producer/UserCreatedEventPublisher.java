package com.cs3219.foc.user.infrastructure.messaging.producer;

import com.cs3219.foc.user.config.EventPublicationProperties;
import com.cs3219.foc.user.model.dto.event.UserCreatedEvent;
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
public class UserCreatedEventPublisher {
    public static final String BINDING = "userCreated-out-0";
    private final StreamBridge streamBridge;
    private final EventPublicationProperties properties;

    @ApplicationModuleListener(id = "user-created-rabbit")
    public void publish(UserCreatedEvent event) {
        // Correlation is per attempt; the business event ID remains unchanged on every retry.
        var correlation = new CorrelationData();
        var message = MessageBuilder.withPayload(event)
                .setHeader(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION, correlation)
                .setHeader("eventId", event.eventId().toString())
                .build();
        if (!streamBridge.send(BINDING, message)) {
            throw new IllegalStateException("User-created message was not accepted for publication");
        }
        try {
            var confirm =
                    correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw new IllegalStateException("User-created message was rejected or unroutable");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("User-created publication interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("User-created publication was not confirmed", exception);
        }
    }
}
