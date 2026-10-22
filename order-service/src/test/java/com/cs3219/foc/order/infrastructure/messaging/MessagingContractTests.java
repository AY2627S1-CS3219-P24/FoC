package com.cs3219.foc.order.infrastructure.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cs3219.foc.order.config.EventMessagingProperties;
import com.cs3219.foc.order.exception.ValidationFailedException;
import com.cs3219.foc.order.infrastructure.messaging.consumer.SqsCreditSettlementResultConsumer;
import com.cs3219.foc.order.infrastructure.messaging.producer.SnsErrandEventPublisher;
import com.cs3219.foc.order.model.dto.event.*;
import com.cs3219.foc.order.service.OrderService;
import io.awspring.cloud.sns.core.SnsOperations;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.MessagingException;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
    private final OffsetDateTime occurredAt = OffsetDateTime.parse("2026-10-22T10:00:00Z");
    private final OrderService service = mock(OrderService.class);
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    private SqsCreditSettlementResultConsumer consumer;

    @BeforeEach
    void setUp() {
        validator.afterPropertiesSet();
        consumer = new SqsCreditSettlementResultConsumer(json, new EventValidator(validator), service);
    }

    @AfterEach
    void closeValidator() {
        validator.close();
    }

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
                .isInstanceOf(ValidationFailedException.class);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYOUT", "REFUND"})
    void validResultsReachTheOrderService(String settlementType) {
        var event = new CreditSettlementResultEvent(
                eventId,
                1,
                occurredAt,
                new CreditSettlementResultEvent.Payload(
                        UUID.randomUUID(),
                        errandId,
                        requesterId,
                        80,
                        settlementType.equals("PAYOUT") ? "PAID" : "REFUNDED",
                        settlementType,
                        settlementType.equals("PAYOUT") ? courierId : null));
        consumer.consume(CreditSettlementResultEvent.TYPE, json.writeValueAsString(event));
        verify(service).handleSettlementResult(event);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "eventId",
                "occurredAt",
                "payload",
                "payload.operationId",
                "payload.errandId",
                "payload.requesterId",
                "payload.outcome",
                "payload.settlementType"
            })
    void missingOrNullFieldsAreRejectedBeforeTheOrderService(String field) {
        var body = validResultBody();
        var separator = field.indexOf('.');
        var parent = separator < 0 ? body : (ObjectNode) body.get("payload");
        var name = separator < 0 ? field : field.substring(separator + 1);
        parent.putNull(name);
        assertInvalidResult(body, field);
        parent.remove(name);
        assertInvalidResult(body, field);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"outcome", "settlementType"})
    void blankPayloadFieldsAreRejected(String field) {
        var body = validResultBody();
        ((ObjectNode) body.get("payload")).put(field, "  ");
        assertInvalidResult(body, "payload." + field);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveAmountsAreRejected(long amount) {
        var body = validResultBody();
        ((ObjectNode) body.get("payload")).put("amount", amount);
        assertInvalidResult(body, "payload.amount");
        verifyNoInteractions(service);
    }

    @Test
    void orderServiceFailuresPropagateToTheListener() {
        var failure = new IllegalStateException("Database unavailable");
        doThrow(failure).when(service).handleSettlementResult(any());
        assertThatThrownBy(() -> consumer.consume(
                        CreditSettlementResultEvent.TYPE, validResultBody().toString()))
                .isSameAs(failure);
    }

    private ObjectNode validResultBody() {
        return (ObjectNode) json.valueToTree(new CreditSettlementResultEvent(
                eventId,
                1,
                occurredAt,
                new CreditSettlementResultEvent.Payload(
                        UUID.randomUUID(), errandId, requesterId, 80, "PAID", "PAYOUT", courierId)));
    }

    private void assertInvalidResult(ObjectNode body, String field) {
        assertThatThrownBy(() -> consumer.consume(CreditSettlementResultEvent.TYPE, body.toString()))
                .isInstanceOfSatisfying(ValidationFailedException.class, exception -> {
                    assertThat(exception.getValidationErrors().getFieldErrors())
                            .extracting(FieldError::getField)
                            .contains(field);
                });
    }
}
