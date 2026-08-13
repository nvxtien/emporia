package com.emporia.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcFixMessageLogStoreTest {
    private static final String SCHEMA = "emporia_execution";

    private Connection connection;
    private JdbcFixMessageLogStore store;

    @BeforeEach
    void setUp() throws Exception {
        String databaseName = "fix_message_log_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        // Applies the real V3 migration DDL directly rather than through Flyway, since V1's
        // Postgres-only partial index isn't H2-compatible and is unrelated to this table.
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            statement.execute("SET SCHEMA " + SCHEMA);
            statement.execute(readMigration());
        }
        store = new JdbcFixMessageLogStore(new JdbcTemplate(dataSource));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void recordedMessagesComeBackWithTheSameFieldsAndOrdering() {
        LinkedHashMap<Integer, String> fields = new LinkedHashMap<>();
        fields.put(11, "order-1");
        fields.put(55, "AAPL");
        fields.put(54, "1");
        Instant sentAt = Instant.parse("2026-08-01T10:00:00Z");

        store.record("XNAS", 5, "D", fields, sentAt);
        List<FixMessageLogStore.LoggedMessage> logged = store.range("XNAS", 5, 5);

        assertThat(logged).hasSize(1);
        FixMessageLogStore.LoggedMessage message = logged.get(0);
        assertThat(message.seqNum()).isEqualTo(5);
        assertThat(message.msgType()).isEqualTo("D");
        assertThat(message.sentAt()).isEqualTo(sentAt);
        assertThat(message.fields()).containsExactly(
                java.util.Map.entry(11, "order-1"), java.util.Map.entry(55, "AAPL"), java.util.Map.entry(54, "1"));
    }

    @Test
    void rangeOnlyReturnsMessagesWithinBoundsAndSkipsGaps() {
        store.record("XNAS", 3, "D", orderFields("a"), Instant.now());
        store.record("XNAS", 5, "D", orderFields("b"), Instant.now());
        store.record("XNAS", 7, "F", orderFields("c"), Instant.now());

        List<FixMessageLogStore.LoggedMessage> logged = store.range("XNAS", 3, 6);

        assertThat(logged).extracting(FixMessageLogStore.LoggedMessage::seqNum).containsExactly(3, 5);
    }

    @Test
    void venuesDoNotShareLoggedMessages() {
        store.record("XNAS", 1, "D", orderFields("a"), Instant.now());
        store.record("XNYS", 1, "D", orderFields("b"), Instant.now());

        assertThat(store.range("XNAS", 1, 1)).hasSize(1);
        assertThat(store.range("XNYS", 1, 1)).hasSize(1);
    }

    @Test
    void clearRemovesOnlyThatVenuesMessages() {
        store.record("XNAS", 1, "D", orderFields("a"), Instant.now());
        store.record("XNYS", 1, "D", orderFields("b"), Instant.now());

        store.clear("XNAS");

        assertThat(store.range("XNAS", 1, 1)).isEmpty();
        assertThat(store.range("XNYS", 1, 1)).hasSize(1);
    }

    private static LinkedHashMap<Integer, String> orderFields(String clientOrderId) {
        LinkedHashMap<Integer, String> fields = new LinkedHashMap<>();
        fields.put(11, clientOrderId);
        return fields;
    }

    private static String readMigration() throws Exception {
        try (InputStream stream = JdbcFixMessageLogStoreTest.class
                .getResourceAsStream("/db/migration/execution/V3__create_fix_message_log.sql")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
