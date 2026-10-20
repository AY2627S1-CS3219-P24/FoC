package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import com.cs3219.foc.credit.model.dto.event.ErrandCancelledEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandCompletedEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandExpiredEvent;
import com.cs3219.foc.credit.model.dto.event.UserCreatedEvent;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class SqsCreditEventConsumer {
    private final ObjectMapper objectMapper;
    private final UserCreatedConsumer userCreatedConsumer;
    private final ErrandSettlementConsumer errandSettlementConsumer;

    @SqsListener("${events.messaging.queue-name}")
    public void consume(@Header("eventType") String eventType, String body) {
        switch (eventType) {
            case UserCreatedEvent.TYPE -> {
                var event = objectMapper.readValue(body, UserCreatedEvent.class);
                userCreatedConsumer.initializeAccount(event);
            }
            case ErrandCancelledEvent.TYPE -> {
                var event = objectMapper.readValue(body, ErrandCancelledEvent.class);
                errandSettlementConsumer.releaseReservedCredits(event);
            }
            case ErrandExpiredEvent.TYPE -> {
                var event = objectMapper.readValue(body, ErrandExpiredEvent.class);
                errandSettlementConsumer.releaseReservedCredits(event);
            }
            case ErrandCompletedEvent.TYPE -> {
                var event = objectMapper.readValue(body, ErrandCompletedEvent.class);
                errandSettlementConsumer.transferReservedCredits(event);
            }
            default -> throw new IllegalArgumentException("Unexpected event type: " + eventType);
        }
    }
}
