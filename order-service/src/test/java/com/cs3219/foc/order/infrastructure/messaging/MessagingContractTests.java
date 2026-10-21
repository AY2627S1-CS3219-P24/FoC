package com.cs3219.foc.order.infrastructure.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cs3219.foc.order.config.EventMessagingProperties;
import com.cs3219.foc.order.infrastructure.messaging.consumer.CreditSettlementResultConsumer;
import com.cs3219.foc.order.infrastructure.messaging.producer.SnsErrandEventPublisher;
import com.cs3219.foc.order.model.dto.event.*;
import com.cs3219.foc.order.service.OrderService;
import io.awspring.cloud.sns.core.SnsOperations;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessagingException;
import tools.jackson.databind.json.JsonMapper;

class MessagingContractTests {
    private static final String TOPIC = "arn:aws:sns:ap-southeast-1:000000000000:foc-dev-order-events";
    private final SnsOperations sns = mock(SnsOperations.class);
    private final SnsErrandEventPublisher publisher =
            new SnsErrandEventPublisher(sns, new EventMessagingProperties(TOPIC, "orders"));
    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID eventId = UUID.randomUUID();
    private final UUID errandId = UUID.randomUUID();
    private final UUID requesterId = UUID.randomUUID();
    private final UUID courierId = UUID.randomUUID();
    private final OffsetDateTime occurredAt = OffsetDateTime.now();

    @Test
    void publisherFailuresRemainVisibleToThePublicationRegistry() {
        var failure = new MessagingException("SNS unavailable");
        doThrow(failure).when(sns).sendNotification(anyString(), any());
        assertThatThrownBy(() -> publisher.publish(new ErrandCancelledEvent(
                        eventId, 1, occurredAt, new ErrandCancelledEvent.Payload(errandId, requesterId, 80))))
                .isSameAs(failure);
    }

    @Test
    void invalidResultsNeverReachTheOrderService() {
        var service = mock(OrderService.class);
        var consumer = new CreditSettlementResultConsumer(json, service);
        assertThatThrownBy(() -> consumer.consume("UnknownEvent", "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.consume(CreditSettlementResultEvent.TYPE, "not json"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> consumer.consume(CreditSettlementResultEvent.TYPE, "{}"))
                .isInstanceOf(RuntimeException.class);
        var future = new CreditSettlementResultEvent(
                eventId,
                2,
                occurredAt,
                new CreditSettlementResultEvent.Payload(
                        UUID.randomUUID(), errandId, requesterId, 80, "PAID", "PAYOUT", courierId));
        assertThatThrownBy(() -> consumer.consume(CreditSettlementResultEvent.TYPE, json.writeValueAsString(future)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }
}
