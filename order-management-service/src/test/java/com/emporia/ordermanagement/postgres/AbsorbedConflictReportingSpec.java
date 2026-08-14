package com.emporia.ordermanagement.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies against a real PostgreSQL that a row absorbed by
 * {@code ON CONFLICT DO NOTHING} comes back as zero affected rows through a
 * JDBC batch.
 *
 * <p>This is an opt-in integration specification selected by the
 * {@code postgres-it} Maven profile.
 *
 * <h2>Why this exists</h2>
 * <p>Both duplicate oracles in {@code AsyncDbWriter} rest entirely on this one
 * behaviour: {@code emporia.oms.dedup.duplicate_reached_db} for command ids and
 * {@code emporia.oms.dedup.duplicate_order_reached_db} for order ids. Neither
 * had ever been shown to fire. A counter reading zero over 72,000 orders proves
 * only that it does not report duplicates that did not happen - the branch that
 * reports one that did had never run, in a test or in a benchmark.
 *
 * <p>The unit tests hand {@code batchUpdate} its return value, so they cover
 * what the writer does with the counts, not whether PostgreSQL and the driver
 * produce them. That is the part that needs a real database: batched statements
 * return counts in driver-chosen groups, and a driver is permitted to answer
 * {@code SUCCESS_NO_INFO} instead of a row count, which would read as a
 * duplicate on every row rather than none.
 *
 * <h2>No Spring context, deliberately</h2>
 * <p>The question is about PostgreSQL and the driver, not about this
 * application, so the test wires a {@code JdbcTemplate} straight onto the
 * container. That also keeps it running: the {@code @DataJpaTest} slice the
 * neighbouring specification uses no longer loads, because the explicit
 * {@code @ComponentScan} added when execution-service was merged in overrides
 * the slice's type-exclude filters and drags the whole application into a
 * persistence-only context.
 *
 * <p>A scratch table rather than {@code trading_order}: the question is about
 * the conflict clause and the batch, not about thirty-four columns.
 */
@Testcontainers
public class AbsorbedConflictReportingSpec {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void aRowAbsorbedByDoNothingIsReportedAsZeroAffectedRows() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS absorbed_conflict_probe (id UUID PRIMARY KEY)");
        jdbcTemplate.execute("TRUNCATE absorbed_conflict_probe");

        UUID fresh = UUID.randomUUID();
        UUID repeated = UUID.randomUUID();
        String sql = "INSERT INTO absorbed_conflict_probe (id) VALUES (?) ON CONFLICT (id) DO NOTHING";

        // The repeated id goes in first, exactly as an order already in the
        // table would be, so its second write is the one that must report zero.
        jdbcTemplate.update(sql, repeated);

        int[][] affected = jdbcTemplate.batchUpdate(sql, List.of(fresh, repeated), 2,
                (statement, id) -> statement.setObject(1, id));

        assertThat(flatten(affected)).containsExactly(1, 0);
    }

    /**
     * The writer walks whatever groups the driver returns rather than assuming
     * one, so the assertion does too.
     */
    private static int[] flatten(int[][] groups) {
        int total = 0;
        for (int[] group : groups) total += group.length;
        int[] counts = new int[total];
        int index = 0;
        for (int[] group : groups) {
            for (int rows : group) counts[index++] = rows;
        }
        return counts;
    }
}
