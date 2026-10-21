package com.cs3219.foc.credit.service;

import static org.assertj.core.api.Assertions.*;

import com.cs3219.foc.credit.config.CreditProperties;
import com.cs3219.foc.credit.exception.*;
import com.cs3219.foc.credit.integration.AbstractIntegrationTest;
import com.cs3219.foc.credit.model.dto.*;
import com.cs3219.foc.credit.model.dto.event.*;
import com.cs3219.foc.credit.repository.CreditAccountRepository;
import com.cs3219.foc.credit.security.AuthenticatedUserResolver;
import com.cs3219.foc.credit.security.AuthenticationContextService;
import com.cs3219.foc.credit.security.CurrentUserService;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;

@org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
@org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.context.annotation.Import(CreditSettlementPersistenceTests.TestConfig.class)
class CreditSettlementPersistenceTests extends AbstractIntegrationTest {

    @Autowired
    private CreditReservationService reserve;

    @Autowired
    private CreditSettlementService settlement;

    @Autowired
    private CreditAccountService creditAccountService;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE credit_accounts CASCADE");
    }

    private UUID fund(long amount) {
        var user = UUID.randomUUID();
        creditAccountService.initializeAccount(user);
        jdbc.update("UPDATE credit_accounts SET total_balance = ? WHERE user_id = ?", amount, user);
        return user;
    }

    private ReserveCreditRequest request(long amount) {
        return new ReserveCreditRequest(UUID.randomUUID(), UUID.randomUUID(), amount);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void concurrent(Runnable first, Runnable second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                first.run();
                return true;
            });
            var b = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                second.run();
                return true;
            });
            assertThat(a.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Autowired
    private Results results;

    @BeforeEach
    void clearResults() {
        results.events.clear();
    }

    static class Results {
        final CopyOnWriteArrayList<CreditSettlementResultEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(CreditSettlementResultEvent event) {
            events.add(event);
        }
    }

    private ErrandCancelledEvent cancelled(UUID user, ReserveCreditRequest r) {
        return new ErrandCancelledEvent(
                UUID.randomUUID(),
                1,
                OffsetDateTime.now(),
                new ErrandCancelledEvent.Payload(r.errandId(), user, r.amount()));
    }

    private ErrandExpiredEvent expired(UUID user, ReserveCreditRequest r) {
        return new ErrandExpiredEvent(
                UUID.randomUUID(),
                1,
                OffsetDateTime.now(),
                new ErrandExpiredEvent.Payload(r.errandId(), user, r.amount()));
    }

    private ErrandCompletedEvent completed(UUID user, ReserveCreditRequest r, UUID courier) {
        return new ErrandCompletedEvent(
                UUID.randomUUID(),
                1,
                OffsetDateTime.now(),
                new ErrandCompletedEvent.Payload(r.errandId(), user, r.amount(), courier));
    }

    private ReserveCreditRequest reserved(UUID user, long amount) {
        var r = request(amount);
        assertThat(reserve.reserve(user, r).outcome()).isEqualTo("RESERVED");
        return r;
    }

    private String outcome() {
        return results.events.getLast().payload().outcome();
    }

    @Test
    void cancellationAndExpiryRefundOnceIncludingDifferentEvents() throws Exception {
        var user = fund(100);
        var r = reserved(user, 80);
        var cancel = cancelled(user, r);
        concurrent(
                () -> settlement.releaseReservedCredits(cancel),
                () -> settlement.releaseReservedCredits(expired(user, r)));
        settlement.releaseReservedCredits(cancel);
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT transaction_type FROM credit_transaction_log", String.class))
                .containsExactlyInAnyOrder("RESERVE", "REFUND");
        assertThat(reserve.reserve(user, r).outcome()).isEqualTo("RESERVED");
    }

    @Test
    void payoutTransfersOnceAndRejectsDifferentCourierOrTerminalEffect() {
        var user = fund(100);
        var courier = fund(7);
        var other = fund(0);
        var r = reserved(user, 80);
        var paid = completed(user, r, courier);
        settlement.transferReservedCredits(paid);
        settlement.transferReservedCredits(paid);
        assertThat(outcome()).isEqualTo("PAID");
        assertThat(results.events.getLast().payload().operationId()).isEqualTo(paid.eventId());
        settlement.transferReservedCredits(completed(user, r, courier));
        assertThat(outcome()).isEqualTo("PAID");
        settlement.transferReservedCredits(completed(user, r, other));
        assertThat(outcome()).isEqualTo("TERMINAL_CONFLICT");
        settlement.releaseReservedCredits(cancelled(user, r));
        assertThat(outcome()).isEqualTo("TERMINAL_CONFLICT");
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 0));
        assertThat(creditAccountService.getBalance(courier)).isEqualTo(new CreditBalanceResponse(87, 0));
        assertThat(creditAccountService.getBalance(other)).isEqualTo(new CreditBalanceResponse(0, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(2);
    }

    @Test
    void missingCourierRollsBackThenQueueRedeliveryCanSucceed() {
        var user = fund(100);
        var courier = UUID.randomUUID();
        var r = reserved(user, 80);
        var paid = completed(user, r, courier);
        assertThatThrownBy(() -> settlement.transferReservedCredits(paid))
                .isInstanceOf(CreditAccountPendingException.class);
        assertThat(results.events).isEmpty();
        assertThat(count("credit_transaction_log")).isEqualTo(1);
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
        creditAccountService.initializeAccount(courier);
        settlement.transferReservedCredits(paid);
        assertThat(creditAccountService.getBalance(courier)).isEqualTo(new CreditBalanceResponse(95, 0));
    }

    @Test
    void settlementBeforeReservationRetriesAndRejectedReservationHasNoEffect() {
        var user = fund(100);
        var r = request(80);
        var cancel = cancelled(user, r);
        assertThatThrownBy(() -> settlement.releaseReservedCredits(cancel)).isInstanceOf(IllegalStateException.class);
        reserve.reserve(user, r);
        settlement.releaseReservedCredits(cancel);
        assertThat(outcome()).isEqualTo("REFUNDED");
        var rejected = request(200);
        reserve.reserve(user, rejected);
        settlement.releaseReservedCredits(cancelled(user, rejected));
        assertThat(outcome()).isEqualTo("NO_RESERVATION");
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(2);
    }

    @Test
    void mismatchedReservationCannotMoveCredits() {
        var user = fund(100);
        var r = reserved(user, 80);
        for (long amount : new long[] {70, 0, -1}) {
            var wrongAmount = new ReserveCreditRequest(r.operationId(), r.errandId(), amount);
            settlement.releaseReservedCredits(cancelled(user, wrongAmount));
            assertThat(outcome()).isEqualTo("RESERVATION_MISMATCH");
            settlement.transferReservedCredits(completed(user, wrongAmount, UUID.randomUUID()));
            assertThat(outcome()).isEqualTo("RESERVATION_MISMATCH");
        }
        settlement.releaseReservedCredits(cancelled(UUID.randomUUID(), r));
        assertThat(outcome()).isEqualTo("RESERVATION_MISMATCH");
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
        assertThat(count("credit_transaction_log")).isEqualTo(1);
    }

    @Test
    void correctedRejectedEventPublishesDistinctResultIdentity() {
        var user = fund(100);
        var r = reserved(user, 80);
        var corrected = cancelled(user, r);
        var rejected = new ErrandCancelledEvent(
                corrected.eventId(),
                1,
                corrected.occurredAt(),
                new ErrandCancelledEvent.Payload(r.errandId(), user, 70));

        settlement.releaseReservedCredits(rejected);
        var rejectedResult = results.events.getLast();
        assertThat(rejectedResult.payload().outcome()).isEqualTo("RESERVATION_MISMATCH");
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));

        settlement.releaseReservedCredits(corrected);
        var correctedResult = results.events.getLast();
        assertThat(correctedResult.payload().outcome()).isEqualTo("REFUNDED");
        assertThat(correctedResult.payload().operationId()).isEqualTo(corrected.eventId());
        assertThat(correctedResult.payload().operationId())
                .isEqualTo(rejectedResult.payload().operationId());
        assertThat(correctedResult.eventId()).isNotEqualTo(rejectedResult.eventId());
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(2);
    }

    @Test
    void processedEventIdCannotBeReusedWithChangedFacts() {
        var user = fund(100);
        var r = reserved(user, 40);
        var second = reserved(user, 40);
        var cancel = cancelled(user, r);
        settlement.releaseReservedCredits(cancel);
        var changed = new ErrandCancelledEvent(
                cancel.eventId(),
                1,
                cancel.occurredAt(),
                new ErrandCancelledEvent.Payload(second.errandId(), user, 40));
        assertThatThrownBy(() -> settlement.releaseReservedCredits(changed))
                .isInstanceOf(IllegalArgumentException.class);
        var changedAmount = new ErrandCancelledEvent(
                cancel.eventId(), 1, cancel.occurredAt(), new ErrandCancelledEvent.Payload(r.errandId(), user, 30));
        assertThatThrownBy(() -> settlement.releaseReservedCredits(changedAmount))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(60, 40));
    }

    @Test
    void recipientWriteFailureRollsBackDebitStateAndAudit() {
        var user = fund(100);
        var courier = fund(0);
        var paid = completed(user, reserved(user, 80), courier);
        jdbc.execute("ALTER TABLE credit_accounts ADD CONSTRAINT fail_recipient CHECK(user_id <> '" + courier
                + "'::uuid OR total_balance=0)");
        try {
            assertThatThrownBy(() -> settlement.transferReservedCredits(paid)).isInstanceOf(RuntimeException.class);
            assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
            assertThat(creditAccountService.getBalance(courier)).isEqualTo(new CreditBalanceResponse(0, 0));
            assertThat(count("credit_transaction_log")).isEqualTo(1);
            assertThat(results.events).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE credit_accounts DROP CONSTRAINT fail_recipient");
        }
        settlement.transferReservedCredits(paid);
        assertThat(outcome()).isEqualTo("PAID");
    }

    @Test
    void auditFailureRollsBackRefund() {
        var user = fund(100);
        var cancel = cancelled(user, reserved(user, 80));
        jdbc.execute("ALTER TABLE credit_transaction_log ADD CONSTRAINT fail_refund CHECK(transaction_type='RESERVE')");
        try {
            assertThatThrownBy(() -> settlement.releaseReservedCredits(cancel)).isInstanceOf(RuntimeException.class);
            assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(20, 80));
            assertThat(count("credit_transaction_log")).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE credit_transaction_log DROP CONSTRAINT fail_refund");
        }
        settlement.releaseReservedCredits(cancel);
        assertThat(creditAccountService.getBalance(user)).isEqualTo(new CreditBalanceResponse(100, 0));
    }

    @Test
    void concurrentRefundAndPaymentHaveOneTerminalEffect() throws Exception {
        var user = fund(100);
        var courier = fund(0);
        var r = reserved(user, 80);
        concurrent(
                () -> settlement.releaseReservedCredits(cancelled(user, r)),
                () -> settlement.transferReservedCredits(completed(user, r, courier)));
        assertThat(results.events.stream().map(e -> e.payload().outcome()).toList())
                .contains("TERMINAL_CONFLICT");
        assertThat(creditAccountService.getBalance(user).reservedBalance()).isZero();
        assertThat(creditAccountService.getBalance(user).availableBalance()
                        + creditAccountService.getBalance(courier).availableBalance())
                .isEqualTo(100);
        assertThat(count("credit_transaction_log")).isEqualTo(2);
    }

    @Test
    void oppositePaymentsUseConsistentAccountLockOrder() throws Exception {
        var a = fund(100);
        var b = fund(100);
        var r = reserved(a, 80);
        var s = reserved(b, 30);
        concurrent(
                () -> settlement.transferReservedCredits(completed(a, r, b)),
                () -> settlement.transferReservedCredits(completed(b, s, a)));
        assertThat(creditAccountService.getBalance(a)).isEqualTo(new CreditBalanceResponse(50, 0));
        assertThat(creditAccountService.getBalance(b)).isEqualTo(new CreditBalanceResponse(150, 0));
        assertThat(count("credit_transaction_log")).isEqualTo(4);
    }

    @TestConfiguration
    @EnableConfigurationProperties(CreditProperties.class)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = CreditAccountRepository.class)
    @Import({
        CreditReservationService.class,
        CreditAccountService.class,
        CreditSettlementService.class,
        CurrentUserService.class,
        AuthenticationContextService.class,
        AuthenticatedUserResolver.class
    })
    static class TestConfig {
        @Bean
        Results results() {
            return new Results();
        }
    }
}
