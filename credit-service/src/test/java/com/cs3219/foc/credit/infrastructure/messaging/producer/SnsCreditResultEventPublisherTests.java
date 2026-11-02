package com.cs3219.foc.credit.infrastructure.messaging.producer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cs3219.foc.credit.config.EventMessagingProperties;
import com.cs3219.foc.credit.model.dto.event.CreditSettlementResultEvent;
import io.awspring.cloud.sns.core.SnsNotification;
import io.awspring.cloud.sns.core.SnsOperations;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessagingException;

class SnsCreditResultEventPublisherTests {
    private static final String TOPIC = "arn:aws:sns:ap-southeast-1:000000000000:foc-dev-credit-events";
    private final SnsOperations sns = mock(SnsOperations.class);
    private final CreditSettlementResultEvent event = new CreditSettlementResultEvent(
            UUID.randomUUID(),
            1,
            OffsetDateTime.now(),
            new CreditSettlementResultEvent.Payload(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 80, "REFUNDED", "REFUND", null));
    private final SnsCreditResultEventPublisher publisher =
            new SnsCreditResultEventPublisher(sns, new EventMessagingProperties(TOPIC, "foc-dev-credit-events"));

    @Test
    void publishesEventTypeAndEventId() {
        publisher.publish(event);
        ArgumentCaptor<SnsNotification<CreditSettlementResultEvent>> notification = ArgumentCaptor.captor();
        verify(sns).sendNotification(eq(TOPIC), notification.capture());
        var sent = notification.getValue();
        var eventId = event.eventId().toString();
        assertThat(sent.getPayload()).isEqualTo(event);
        assertThat(sent.getHeaders())
                .containsEntry("eventType", CreditSettlementResultEvent.TYPE)
                .containsEntry("eventId", eventId);
    }

    @Test
    void publishFailurePropagates() {
        doThrow(new MessagingException("sns down")).when(sns).sendNotification(any(), any());
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(MessagingException.class);
    }
}
