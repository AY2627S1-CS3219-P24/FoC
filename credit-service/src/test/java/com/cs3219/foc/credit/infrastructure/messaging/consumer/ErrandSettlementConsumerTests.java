package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cs3219.foc.credit.model.dto.event.ErrandSettlementEvent;
import com.cs3219.foc.credit.service.CreditSettlementService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ErrandSettlementConsumerTests {
    private final CreditSettlementService service = mock(CreditSettlementService.class);
    private final ErrandSettlementConsumer consumers = new ErrandSettlementConsumer();

    private ErrandSettlementEvent contract(String type, UUID courier) {
        var json = """
                {
                  "eventId": "00000000-0000-0000-0000-000000000001",
                  "eventType": "%s",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-18T01:00:00Z",
                  "payload": {
                    "operationId": "00000000-0000-0000-0000-000000000002",
                    "reservationOperationId": "00000000-0000-0000-0000-000000000003",
                    "errandId": "00000000-0000-0000-0000-000000000004",
                    "requesterId": "00000000-0000-0000-0000-000000000005",
                    "amount": 80,
                    "courierId": %s
                  }
                }
                """.formatted(type, courier == null ? "null" : "\"" + courier + "\"");
        var event = JsonMapper.builder().build().readValue(json, ErrandSettlementEvent.class);
        assertThat(event)
                .isEqualTo(new ErrandSettlementEvent(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        type,
                        1,
                        OffsetDateTime.parse("2026-10-18T01:00:00Z"),
                        new ErrandSettlementEvent.Payload(
                                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                                80,
                                courier)));
        return event;
    }

    @Test
    void cancellationRoutesToRelease() {
        var event = contract("ErrandCancelledEvent", null);
        consumers.errandCancelled(service).accept(event);
        verify(service).releaseReservedCredits(event);
        verifyNoMoreInteractions(service);
    }

    @Test
    void expiryRoutesToRelease() {
        var event = contract("ErrandExpiredEvent", null);
        consumers.errandExpired(service).accept(event);
        verify(service).releaseReservedCredits(event);
        verifyNoMoreInteractions(service);
    }

    @Test
    void completionRoutesToTransfer() {
        var event = contract("ErrandCompletedEvent", UUID.randomUUID());
        consumers.errandCompleted(service).accept(event);
        verify(service).transferReservedCredits(event);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ErrandCancelledEvent", "ErrandExpiredEvent", "ErrandCompletedEvent"})
    void propagatesTransactionFailureSoRabbitDoesNotAcknowledgeSuccess(String type) {
        var event = contract(type, type.equals("ErrandCompletedEvent") ? UUID.randomUUID() : null);
        var failure = new IllegalStateException("database unavailable");
        var consumer =
                switch (type) {
                    case "ErrandCancelledEvent" -> consumers.errandCancelled(service);
                    case "ErrandExpiredEvent" -> consumers.errandExpired(service);
                    default -> consumers.errandCompleted(service);
                };
        if (type.equals("ErrandCompletedEvent")) {
            doThrow(failure).when(service).transferReservedCredits(event);
        } else {
            doThrow(failure).when(service).releaseReservedCredits(event);
        }
        assertThatThrownBy(() -> consumer.accept(event)).isSameAs(failure);
    }
}
