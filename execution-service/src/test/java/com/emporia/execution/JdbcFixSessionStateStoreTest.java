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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcFixSessionStateStoreTest {
    private static final String SCHEMA = "emporia_execution";

    private Connection connection;
    private AtomicReference<Instant> now;
    private JdbcFixSessionStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        String databaseName = "fix_session_state_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        // Applies the real V2 migration DDL directly rather than through Flyway, since V1's
        // Postgres-only partial index isn't H2-compatible and is unrelated to fix_session_state.
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            statement.execute("SET SCHEMA " + SCHEMA);
            statement.execute(readMigration());
        }

        now = new AtomicReference<>(Instant.parse("2026-08-01T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        store = new JdbcFixSessionStateStore(new JdbcTemplate(dataSource), clock);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private static String readMigration() throws Exception {
        try (InputStream stream = JdbcFixSessionStateStoreTest.class
                .getResourceAsStream("/db/migration/V2__create_fix_session_state.sql")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void firstLoadForAnUnknownVenueStartsAFreshSession() {
        FixSessionStateStore.SessionState state = store.loadForToday("XNAS");

        assertThat(state.outgoingSeqNum()).isEqualTo(1);
        assertThat(state.incomingSeqNum()).isEqualTo(1);
        assertThat(state.freshSession()).isTrue();
    }

    @Test
    void savedSequenceNumbersSurviveAReloadOnTheSameDay() {
        store.loadForToday("XNAS");
        store.saveOutgoing("XNAS", 5);
        store.saveIncoming("XNAS", 4);

        FixSessionStateStore.SessionState reloaded = store.loadForToday("XNAS");

        assertThat(reloaded.outgoingSeqNum()).isEqualTo(5);
        assertThat(reloaded.incomingSeqNum()).isEqualTo(4);
        assertThat(reloaded.freshSession()).isFalse();
    }

    @Test
    void aNewUtcDayResetsSequenceNumbersToOne() {
        store.loadForToday("XNAS");
        store.saveOutgoing("XNAS", 42);
        store.saveIncoming("XNAS", 41);

        now.set(now.get().plusSeconds(24 * 60 * 60));
        FixSessionStateStore.SessionState nextDay = store.loadForToday("XNAS");

        assertThat(nextDay.outgoingSeqNum()).isEqualTo(1);
        assertThat(nextDay.incomingSeqNum()).isEqualTo(1);
        assertThat(nextDay.freshSession()).isTrue();
    }

    @Test
    void venuesTrackIndependentSequenceNumbers() {
        store.loadForToday("XNAS");
        store.loadForToday("XNYS");
        store.saveOutgoing("XNAS", 9);

        assertThat(store.loadForToday("XNAS").outgoingSeqNum()).isEqualTo(9);
        assertThat(store.loadForToday("XNYS").outgoingSeqNum()).isEqualTo(1);
    }
}
