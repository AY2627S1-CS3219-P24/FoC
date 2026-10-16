package com.cs3219.foc.order.infrastructure.messaging.producer;

import com.cs3219.foc.order.config.OrderCreditProperties;
import com.cs3219.foc.order.model.dto.event.ErrandSubmittedEvent;
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
public class ErrandSubmittedEventPublisher {
    public static final String BINDING = "errandSubmitted-out-0";
    private final StreamBridge streamBridge;
    private final OrderCreditProperties properties;

    public void publish(ErrandSubmittedEvent event) {
        // Correlation is per attempt; the business event ID remains unchanged on every retry.
        var correlation = new CorrelationData();
        var message = MessageBuilder.withPayload(event)
                .setHeader(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION, correlation)
                .setHeader("eventId", event.eventId().toString())
                .build();
        if (!streamBridge.send(BINDING, message)) {
            throw new IllegalStateException("Errand-submitted message was not accepted for publication");
        }
        try {
            var confirm =
                    correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw new IllegalStateException("Errand-submitted message was rejected or unroutable");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Errand-submitted publication interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Errand-submitted publication was not confirmed", exception);
        }
    }
}
