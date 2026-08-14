package com.emporia.ordermanagement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Fills {@link CommandDedupIndex} from Postgres so the hot path can stop asking
 * it questions one order at a time.
 *
 * <p>The point is the shape of the access, not its speed: one bulk read at
 * startup replaces a per-order round trip that costs 1.313 ms on the single
 * writer thread, where it multiplies by the queue depth.
 *
 * <p>Reads only {@code command_id}. The rest of the row is the previous result,
 * which matters solely for a retry inside the client's retry window - and that
 * window is served by the exact tier, populated as commands are processed. A
 * session's worth of payloads would be gigabytes for a question that is only
 * ever "yes or no".
 *
 * <p>Bounded by {@code processed_at}, which the V10 index supports. That is
 * sound because exactly one instance accepts orders, so every row in the window
 * came from a single clock - enforced on the order path, not assumed.
 */
public class DedupIndexLoader {

    private static final Logger log = LoggerFactory.getLogger(DedupIndexLoader.class);

    private static final String SELECT_SESSION = """
            SELECT command_id
              FROM emporia_order_data.processed_order_command
             WHERE processed_at > ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public DedupIndexLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Streams the window's command ids into {@code index} and returns how many
     * were loaded.
     *
     * <p>Streamed rather than collected: a session at 1,000 orders/sec is 28.8
     * million ids, and materialising them into a list before inserting them
     * would cost more memory than the filter they are going into.
     *
     * <p>The count is reported rather than reconciled against a separate
     * {@code COUNT(*)}. The two would legitimately disagree - this instance
     * keeps processing orders while the load runs, and those are written to the
     * index directly - so a mismatch would prove nothing.
     */
    public long load(CommandDedupIndex index, Duration window) {
        Instant since = Instant.now().minus(window);
        long started = System.nanoTime();
        long[] loaded = {0};

        jdbcTemplate.query(SELECT_SESSION, rs -> {
            index.remember((UUID) rs.getObject(1));
            loaded[0]++;
        }, Timestamp.from(since));

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        log.info("Loaded {} processed commands into the deduplication index in {} ms (window={}, since={})",
                loaded[0], elapsedMs, window, since);
        return loaded[0];
    }
}
