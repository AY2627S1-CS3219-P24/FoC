package com.cs3219.foc.credit.infrastructure.messaging.producer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cs3219.foc.credit.config.CreditRewardProperties;
import com.cs3219.foc.credit.model.dto.event.CreditReservationResultEvent;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

class CreditResultEventPublisherTests {
    private final StreamBridge bridge = mock(StreamBridge.class);
    private final CreditReservationResultEvent event = new CreditReservationResultEvent(
            UUID.randomUUID(),
            "CreditReservationResultEvent",
            1,
            OffsetDateTime.now(),
            new CreditReservationResultEvent.Payload(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 80, "RESERVED"));
    private final CreditResultEventPublisher publisher = new CreditResultEventPublisher(
            bridge,
            new CreditRewardProperties(
                    1, 1, Duration.ofMillis(20), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMinutes(5)));

    private void confirm(boolean ack, boolean returned) {
        when(bridge.send(eq(CreditResultEventPublisher.BINDING), any())).thenAnswer(call -> {
            Message<?> message = call.getArgument(1);
            assertThat(message.getPayload()).isEqualTo(event);
            var correlation = (CorrelationData) message.getHeaders().get(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION);
            if (returned)
                correlation.setReturned(new ReturnedMessage(
                        new org.springframework.amqp.core.Message(new byte[0], new MessageProperties()),
                        312,
                        "NO_ROUTE",
                        "exchange",
                        "key"));
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "NACK"));
            return true;
        });
    }

    @Test
    void succeedsOnlyAfterPositiveConfirm() {
        confirm(true, false);
        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }

    @Test
    void returnedMessageIsFailureEvenWhenBrokerAcknowledges() {
        confirm(true, true);
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void negativeConfirmIsFailure() {
        confirm(false, false);
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendReturningFalseIsFailure() {
        when(bridge.send(eq(CreditResultEventPublisher.BINDING), any())).thenReturn(false);
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendReturningTrueWithoutConfirmIsNotSuccess() {
        when(bridge.send(eq(CreditResultEventPublisher.BINDING), any())).thenReturn(true);
        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not confirmed");
    }
}
