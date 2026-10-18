package com.cs3219.foc.credit.infrastructure.messaging.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cs3219.foc.credit.model.dto.event.ErrandSettlementEvent;
import com.cs3219.foc.credit.service.CreditSettlementService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.*;

class ErrandSettlementConsumerTests {
    private final CreditSettlementService service = mock(CreditSettlementService.class);
    private final ErrandSettlementConsumer consumers = new ErrandSettlementConsumer();
    private static ValidatorFactory validators;

    @BeforeAll
    static void setup() {
        validators = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void close() {
        validators.close();
    }

    private ErrandSettlementEvent event(String type, int version, UUID courier, long amount) {
        return new ErrandSettlementEvent(
                UUID.randomUUID(),
                type,
                version,
                OffsetDateTime.now(),
                new ErrandSettlementEvent.Payload(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amount, courier));
    }

    @Test
    void validatesAtBoundaryThenInvokesTransactionalService() {
        var e = event("ErrandCompletedEvent", 1, UUID.randomUUID(), 80);
        consumers.errandCompleted(service, validators.getValidator()).accept(e);
        verify(service).settle(e);
    }

    @Test
    void rejectsIncompleteOrUnsupportedEventsBeforeService() {
        var consumer = consumers.errandCompleted(service, validators.getValidator());
        assertThatThrownBy(() -> consumer.accept(event("ErrandCompletedEvent", 2, UUID.randomUUID(), 80)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> consumer.accept(event("ErrandCompletedEvent", 1, null, 80)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> consumer.accept(event("ErrandCompletedEvent", 1, UUID.randomUUID(), 0)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> consumer.accept(
                        new ErrandSettlementEvent(null, "ErrandCompletedEvent", 1, OffsetDateTime.now(), null)))
                .isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(service);
    }

    @Test
    void rejectsWrongRoutingTypeAndCourierOnRefund() {
        var consumer = consumers.errandCancelled(service, validators.getValidator());
        assertThatThrownBy(() -> consumer.accept(event("ErrandExpiredEvent", 1, null, 80)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.accept(event("UnsupportedEvent", 1, null, 80)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.accept(event("ErrandCancelledEvent", 1, UUID.randomUUID(), 80)))
                .isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(service);
    }

    @Test
    void propagatesTransactionFailureSoRabbitDoesNotAcknowledgeSuccess() {
        var e = event("ErrandExpiredEvent", 1, null, 80);
        doThrow(new IllegalStateException("database unavailable")).when(service).settle(e);
        assertThatThrownBy(() -> consumers
                        .errandExpired(service, validators.getValidator())
                        .accept(e))
                .isInstanceOf(IllegalStateException.class);
    }
}
