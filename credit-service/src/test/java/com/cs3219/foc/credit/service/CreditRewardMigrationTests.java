package com.cs3219.foc.credit.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "FOC_TEST_DATABASE_URL", matches = ".+")
class CreditRewardMigrationTests {
    @Test
    void upgradePreservesRejectedDecisionsAuditAndUnpublishedSettlementResults() {
        var schema = "credit_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var url = System.getenv("FOC_TEST_DATABASE_URL");
        var source = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("FOC_TEST_DATABASE_USERNAME"),
                System.getenv("FOC_TEST_DATABASE_PASSWORD"));
        var sql = new JdbcTemplate(source);
        var location =
                "filesystem:" + System.getProperty("foc.root") + "/credit-service/src/main/resources/db/migration";
        try {
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations(location)
                    .target("6")
                    .load()
                    .migrate();
            var user = UUID.randomUUID();
            var errand = UUID.randomUUID();
            var reserve = UUID.randomUUID();
            var refund = UUID.randomUUID();
            var event = UUID.randomUUID();
            sql.update("INSERT INTO credit_accounts(user_id,total_balance) VALUES (?,100)", user);
            sql.update(
                    "INSERT INTO credit_reserve_operations(operation_id,errand_id,requester_id,amount,outcome,completed_at) VALUES (?,?,?,40,'RESERVED',now())",
                    reserve,
                    errand,
                    user);
            sql.update(
                    "INSERT INTO credit_reservations(errand_id,operation_id,user_id,amount,state,settled_at) VALUES (?,?,?,40,'REFUNDED',now())",
                    errand,
                    reserve,
                    user);
            sql.update(
                    "INSERT INTO credit_settlement_operations(operation_id,reservation_operation_id,errand_id,requester_id,amount,event_type,outcome,completed_at) VALUES (?,?,?,?,40,'ErrandCancelledEvent','REFUNDED',now())",
                    refund,
                    reserve,
                    errand,
                    user);
            sql.update(
                    "INSERT INTO credit_settlement_operations(operation_id,reservation_operation_id,errand_id,requester_id,amount,event_type,outcome,completed_at) VALUES (?,?,?,?,40,'ErrandExpiredEvent','REFUNDED',now()+interval '1 second')",
                    UUID.randomUUID(),
                    reserve,
                    errand,
                    user);
            sql.update(
                    "INSERT INTO credit_result_outbox(event_id,settlement_operation_id,errand_id,requester_id,amount,outcome,settlement_type) VALUES (?,?,?,?,40,'REFUNDED','REFUND')",
                    event,
                    refund,
                    errand,
                    user);
            sql.update(
                    "INSERT INTO credit_reserve_operations(operation_id,errand_id,requester_id,amount,outcome,completed_at) VALUES (?,?,?,200,'INSUFFICIENT_BALANCE',now())",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    user);
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations(location)
                    .load()
                    .migrate();
            assertThat(sql.queryForList("SELECT state FROM credit_reservations", String.class))
                    .containsExactlyInAnyOrder("REFUNDED", "INSUFFICIENT_BALANCE");
            assertThat(sql.queryForList("SELECT transaction_type FROM credit_transaction_log", String.class))
                    .containsExactlyInAnyOrder("RESERVE", "REFUND");
            assertThat(sql.queryForObject("SELECT status FROM event_publication WHERE id=?", String.class, event))
                    .isEqualTo("FAILED");
            assertThat(sql.queryForObject(
                            "SELECT serialized_event::jsonb->>'eventId' FROM event_publication WHERE id=?",
                            String.class,
                            event))
                    .isEqualTo(event.toString());
            for (var table : List.of(
                    "credit_reserve_operations",
                    "credit_reserve_inbox",
                    "credit_settlement_operations",
                    "credit_settlement_inbox",
                    "credit_result_outbox"))
                assertThat(sql.queryForObject("SELECT to_regclass(?)", String.class, table))
                        .isNull();
        } finally {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void upgradeRefusesToDiscardPendingAcknowledgedWork() {
        var schema = "credit_pending_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var url = System.getenv("FOC_TEST_DATABASE_URL");
        var source = new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("FOC_TEST_DATABASE_USERNAME"),
                System.getenv("FOC_TEST_DATABASE_PASSWORD"));
        var sql = new JdbcTemplate(source);
        var location =
                "filesystem:" + System.getProperty("foc.root") + "/credit-service/src/main/resources/db/migration";
        try {
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations(location)
                    .target("6")
                    .load()
                    .migrate();
            sql.update(
                    "INSERT INTO credit_settlement_operations(operation_id,reservation_operation_id,errand_id,requester_id,amount,event_type) VALUES (?,?,?,?,40,'ErrandCancelledEvent')",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID());
            assertThatThrownBy(() -> Flyway.configure()
                            .dataSource(source)
                            .schemas(schema)
                            .defaultSchema(schema)
                            .locations(location)
                            .load()
                            .migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("Drain pending credit");
            assertThat(sql.queryForObject("SELECT count(*) FROM credit_settlement_operations", Long.class))
                    .isEqualTo(1);
        } finally {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
