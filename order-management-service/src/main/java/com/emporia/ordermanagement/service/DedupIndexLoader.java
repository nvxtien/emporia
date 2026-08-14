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
 * <h2>Both key spaces, because the index answers two questions</h2>
 * <p>{@code commandId} decides idempotency; {@code orderId} decides the 409
 * duplicate-order guard. An earlier version loaded only the first, so after a
 * restart every pre-existing orderId read as "never seen" and the guard was
 * silently off - a false negative, the one direction that matters.
 *
 * <p>The order side has two arms. The window arm mirrors the command side.
 * The working arm ignores age deliberately: strategy child slices carry
 * deterministic ids, so a parent outliving the horizon regenerates ids the
 * window no longer holds, and the writer upserts rather than failing on the
 * primary key - the duplicate would overwrite a live order instead of being
 * rejected. That set is small enough to load whole.
 *
 * <p>Reads only the identifier column. The rest of the row is the previous
 * result, which matters solely for a retry inside the client's retry window -
 * and that window is served by the exact tier, populated as commands are
 * processed. A window's worth of payloads would be gigabytes for a question
 * that is only ever "yes or no".
 *
 * <p>Bounded by {@code processed_at} and {@code created_at}, which the V10 and
 * V11 indexes support. That is sound because exactly one instance accepts
 * orders, so every row in the window came from a single clock - enforced on the
 * order path, not assumed.
 */
public class DedupIndexLoader {

    private static final Logger log = LoggerFactory.getLogger(DedupIndexLoader.class);

    private static final String SELECT_COMMANDS = """
            SELECT command_id
              FROM emporia_order_data.processed_order_command
             WHERE processed_at > ?
            """;

    private static final String SELECT_RECENT_ORDERS = """
            SELECT id
              FROM emporia_order_data.trading_order
             WHERE created_at > ?
            """;

    private static final String SELECT_WORKING_ORDERS = """
            SELECT id
              FROM emporia_order_data.trading_order
             WHERE order_status IN ('LIVE', 'PARTIALLY_FILLED')
            """;

    private final JdbcTemplate jdbcTemplate;

    public DedupIndexLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Streams the window's identifiers into {@code index} and returns how many
     * were loaded across both key spaces.
     *
     * <p>Streamed rather than collected: a day at 120 orders/sec is over ten
     * million ids per key space, and materialising them into a list before
     * inserting them would cost more memory than the filter they are going
     * into.
     *
     * <p>The count is reported rather than reconciled against a separate
     * {@code COUNT(*)}. The two would legitimately disagree - this instance
     * keeps processing orders while the load runs, and those are written to the
     * index directly - so a mismatch would prove nothing. The working-order arm
     * also overlaps the window arm, and a Bloom filter absorbs the repeat
     * without noticing.
     */
    public long load(CommandDedupIndex index, Duration window) {
        Instant since = Instant.now().minus(window);
        Timestamp from = Timestamp.from(since);
        long started = System.nanoTime();

        long commands = stream(SELECT_COMMANDS, index, from);
        long recentOrders = stream(SELECT_RECENT_ORDERS, index, from);
        long workingOrders = stream(SELECT_WORKING_ORDERS, index);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        log.info("Loaded {} processed commands, {} recent orders and {} working orders into the "
                        + "deduplication index in {} ms (window={}, since={})",
                commands, recentOrders, workingOrders, elapsedMs, window, since);
        return commands + recentOrders + workingOrders;
    }

    private long stream(String sql, CommandDedupIndex index, Object... arguments) {
        long[] loaded = {0};
        jdbcTemplate.query(sql, rs -> {
            index.remember((UUID) rs.getObject(1));
            loaded[0]++;
        }, arguments);
        return loaded[0];
    }
}
