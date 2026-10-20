package com.cs3219.foc.order.infrastructure.messaging.consumer;

import com.cs3219.foc.order.model.dto.event.CreditSettlementResultEvent;
import com.cs3219.foc.order.service.OrderService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class CreditSettlementResultConsumer {
    private final ObjectMapper objectMapper;
    private final OrderService orderService;

    @SqsListener(value = "${events.messaging.queue-name}", acknowledgementMode = "ON_SUCCESS")
    public void consume(@Header("eventType") String eventType, String body) {
        if (!CreditSettlementResultEvent.TYPE.equals(eventType)) {
            throw new IllegalArgumentException("Unexpected event type: " + eventType);
        }
        var event = objectMapper.readValue(body, CreditSettlementResultEvent.class);
        if (event.eventId() == null
                || event.occurredAt() == null
                || event.payload() == null
                || event.schemaVersion() != 1) {
            throw new IllegalArgumentException("Invalid CreditSettlementResultEvent envelope");
        }
        orderService.handleSettlementResult(event);
    }
}
