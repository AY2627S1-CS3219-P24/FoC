package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cs3219.foc.credit.model.dto.event.ErrandCancelledEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandCompletedEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandExpiredEvent;
import com.cs3219.foc.credit.model.dto.event.UserCreatedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SqsCreditEventConsumerTests {
    private final UserCreatedConsumer userCreatedConsumer = mock(UserCreatedConsumer.class);
    private final ErrandSettlementConsumer errandSettlementConsumer = mock(ErrandSettlementConsumer.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final SqsCreditEventConsumer consumer =
            new SqsCreditEventConsumer(objectMapper, userCreatedConsumer, errandSettlementConsumer);

    private String contract(UUID courier) {
        var courierField = courier == null ? "" : ", \"courierId\": \"" + courier + "\"";
        return """
                {
                  "eventId": "00000000-0000-0000-0000-000000000001",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-18T01:00:00Z",
                  "payload": {
                    "errandId": "00000000-0000-0000-0000-000000000004",
                    "requesterId": "00000000-0000-0000-0000-000000000005",
                    "amount": 80%s
                  }
                }
                """.formatted(courierField);
    }

    @Test
    void userCreatedInitializesTheAccount() {
        var body = """
                {
                  "eventId": "00000000-0000-0000-0000-000000000001",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-18T01:00:00Z",
                  "payload": { "userId": "00000000-0000-0000-0000-000000000002" }
                }
                """;
        var event = objectMapper.readValue(body, UserCreatedEvent.class);
        consumer.consume(UserCreatedEvent.TYPE, body);
        verify(userCreatedConsumer).initializeAccount(event);
    }

    @Test
    void cancellationRoutesToRelease() {
        var body = contract(null);
        var event = objectMapper.readValue(body, ErrandCancelledEvent.class);
        consumer.consume(ErrandCancelledEvent.TYPE, body);
        verify(errandSettlementConsumer).releaseReservedCredits(event);
    }

    @Test
    void expiryRoutesToRelease() {
        var body = contract(null);
        var event = objectMapper.readValue(body, ErrandExpiredEvent.class);
        consumer.consume(ErrandExpiredEvent.TYPE, body);
        verify(errandSettlementConsumer).releaseReservedCredits(event);
    }

    @Test
    void completionRoutesToTransfer() {
        var body = contract(UUID.randomUUID());
        var event = objectMapper.readValue(body, ErrandCompletedEvent.class);
        consumer.consume(ErrandCompletedEvent.TYPE, body);
        verify(errandSettlementConsumer).transferReservedCredits(event);
    }

    @Test
    void handlerFailurePropagates() {
        var body = contract(null);
        var event = objectMapper.readValue(body, ErrandCancelledEvent.class);
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(errandSettlementConsumer).releaseReservedCredits(event);
        assertThatThrownBy(() -> consumer.consume(ErrandCancelledEvent.TYPE, body))
                .isSameAs(failure);
    }

    @Test
    void unexpectedEventTypeFails() {
        assertThatThrownBy(() -> consumer.consume("SomethingElse", "{}")).isInstanceOf(IllegalArgumentException.class);
    }
}
