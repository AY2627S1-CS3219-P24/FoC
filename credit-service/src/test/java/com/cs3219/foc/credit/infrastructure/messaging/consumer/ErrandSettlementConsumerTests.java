package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cs3219.foc.credit.model.dto.event.ErrandCancelledEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandCompletedEvent;
import com.cs3219.foc.credit.model.dto.event.ErrandExpiredEvent;
import com.cs3219.foc.credit.service.CreditSettlementService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ErrandSettlementConsumerTests {
    private final CreditSettlementService service = mock(CreditSettlementService.class);
    private final ErrandSettlementConsumer consumers = new ErrandSettlementConsumer();

    private <T> T contract(String type, UUID courier, Class<T> eventClass) {
        var body = """
                {
                  "eventId": "00000000-0000-0000-0000-000000000001",
                  "eventType": "%s",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-18T01:00:00Z",
                  "payload": {
                    "errandId": "00000000-0000-0000-0000-000000000004",
                    "requesterId": "00000000-0000-0000-0000-000000000005",
                    "amount": 80%s
                  }
                }
                """.formatted(type, courier == null ? "" : ", \"courierId\": \"" + courier + "\"");
        var json = JsonMapper.builder().build();
        var event = json.readValue(body, eventClass);
        // Each public contract contains errand facts, without Credit operation IDs or instructions.
        assertThat(json.readTree(json.writeValueAsString(event))).isEqualTo(json.readTree(body));
        return event;
    }

    @Test
    void cancellationRoutesToRelease() {
        var event = contract("ErrandCancelledEvent", null, ErrandCancelledEvent.class);
        consumers.errandCancelled(service).accept(event);
        verify(service).releaseReservedCredits(event);
        verifyNoMoreInteractions(service);
    }

    @Test
    void expiryRoutesToRelease() {
        var event = contract("ErrandExpiredEvent", null, ErrandExpiredEvent.class);
        consumers.errandExpired(service).accept(event);
        verify(service).releaseReservedCredits(event);
        verifyNoMoreInteractions(service);
    }

    @Test
    void completionRoutesToTransfer() {
        var event = contract("ErrandCompletedEvent", UUID.randomUUID(), ErrandCompletedEvent.class);
        consumers.errandCompleted(service).accept(event);
        verify(service).transferReservedCredits(event);
        verifyNoMoreInteractions(service);
    }

    @Test
    void cancellationPropagatesTransactionFailure() {
        var event = contract("ErrandCancelledEvent", null, ErrandCancelledEvent.class);
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(service).releaseReservedCredits(event);
        assertThatThrownBy(() -> consumers.errandCancelled(service).accept(event))
                .isSameAs(failure);
    }

    @Test
    void expiryPropagatesTransactionFailure() {
        var event = contract("ErrandExpiredEvent", null, ErrandExpiredEvent.class);
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(service).releaseReservedCredits(event);
        assertThatThrownBy(() -> consumers.errandExpired(service).accept(event)).isSameAs(failure);
    }

    @Test
    void completionPropagatesTransactionFailure() {
        var event = contract("ErrandCompletedEvent", UUID.randomUUID(), ErrandCompletedEvent.class);
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(service).transferReservedCredits(event);
        assertThatThrownBy(() -> consumers.errandCompleted(service).accept(event))
                .isSameAs(failure);
    }
}
