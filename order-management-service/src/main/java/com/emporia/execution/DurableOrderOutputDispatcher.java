package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Claims committed OMS output records, delivers them, and retries failures. */
@Component
public class DurableOrderOutputDispatcher {
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final ShardedOrderDispatcher dispatcher;
    private final Counter delivered;
    private final Counter retries;
    private final int retentionDays;
    private final AtomicLong pendingDepth = new AtomicLong();
    private final AtomicLong oldestPendingAgeMillis = new AtomicLong();
    private final AtomicLong deliveryLagMillis = new AtomicLong();

    public DurableOrderOutputDispatcher(JdbcTemplate jdbcTemplate,
                                        @org.springframework.context.annotation.Lazy
                                        ShardedOrderDispatcher dispatcher,
                                        MeterRegistry meters,
                                        @org.springframework.beans.factory.annotation.Value(
                                                "${emporia.order-output.retention-days:7}") int retentionDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.dispatcher = dispatcher;
        this.retentionDays = Math.max(1, retentionDays);
        this.delivered = meters.counter("emporia.oms.output.delivered");
        this.retries = meters.counter("emporia.oms.output.retries");
        Gauge.builder("emporia.oms.output.pending.depth", pendingDepth, AtomicLong::get)
                .description("Committed OMS output events waiting for venue delivery")
                .register(meters);
        Gauge.builder("emporia.oms.output.pending.oldest_age_ms", oldestPendingAgeMillis, AtomicLong::get)
                .description("Age of the oldest pending OMS output event")
                .register(meters);
        Gauge.builder("emporia.oms.output.delivery.lag_ms", deliveryLagMillis, AtomicLong::get)
                .description("Age of the oldest output event claimed for delivery")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${emporia.order-output.poll-delay-ms:10}")
    public void poll() {
        List<PendingOutput> claimed = jdbcTemplate.query("""
                WITH claimable AS (
                    SELECT sequence_id
                    FROM emporia_order_data.order_delivery_outbox candidate
                    WHERE ((candidate.status = 'PENDING' AND candidate.next_attempt_at <= CURRENT_TIMESTAMP)
                       OR (candidate.status = 'IN_FLIGHT' AND candidate.lease_until < CURRENT_TIMESTAMP))
                      AND NOT EXISTS (
                          SELECT 1
                          FROM emporia_order_data.order_delivery_outbox earlier
                          WHERE earlier.order_id = candidate.order_id
                            AND earlier.sequence_id < candidate.sequence_id
                            AND earlier.status <> 'DELIVERED'
                      )
                    ORDER BY candidate.sequence_id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE emporia_order_data.order_delivery_outbox outbox
                SET status = 'IN_FLIGHT',
                    attempt_count = attempt_count + 1,
                    lease_until = CURRENT_TIMESTAMP + INTERVAL '1 minute'
                FROM claimable
                WHERE outbox.sequence_id = claimable.sequence_id
                RETURNING outbox.sequence_id, outbox.event_id, outbox.schema_version,
                          outbox.command_id, outbox.order_id, outbox.user_subject,
                          outbox.desk_id, outbox.event_type, outbox.order_version,
                          outbox.order_status, outbox.occurred_at, outbox.payload
        """, this::map, BATCH_SIZE);
        refreshBacklogMetrics();
        claimed.forEach(this::deliver);
    }

    @Scheduled(fixedDelayString = "${emporia.order-output.retention-delay-ms:60000}")
    public void deleteDelivered() {
        jdbcTemplate.update("""
                DELETE FROM emporia_order_data.order_delivery_outbox
                WHERE status = 'DELIVERED'
                  AND delivered_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                """, retentionDays());
    }

    private void deliver(PendingOutput pending) {
        try {
            dispatcher.dispatch(pending.event()).whenComplete((ignored, failure) -> {
                recordResult(pending, failure);
            });
        } catch (RuntimeException rejected) {
            recordResult(pending, rejected);
        }
    }

    private void recordResult(PendingOutput pending, Throwable failure) {
        if (failure == null) {
            jdbcTemplate.update("""
                    UPDATE emporia_order_data.order_delivery_outbox
                    SET status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP,
                        lease_until = NULL, last_error = NULL
                    WHERE event_id = ? AND status = 'IN_FLIGHT'
                        """, pending.event().eventId());
                delivered.increment();
            } else {
            jdbcTemplate.update("""
                    UPDATE emporia_order_data.order_delivery_outbox
                    SET status = 'PENDING', lease_until = NULL,
                        next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 second',
                        last_error = ?
                    WHERE event_id = ? AND status = 'IN_FLIGHT'
                        """, failure.toString(), pending.event().eventId());
                retries.increment();
            }
    }

    private void refreshBacklogMetrics() {
        pendingDepth.set(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM emporia_order_data.order_delivery_outbox
                WHERE status <> 'DELIVERED'
                """, Long.class));
        oldestPendingAgeMillis.set(jdbcTemplate.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(occurred_at))) * 1000, 0)
                FROM emporia_order_data.order_delivery_outbox
                WHERE status <> 'DELIVERED'
                """, (rs, row) -> rs.getLong(1)));
        deliveryLagMillis.set(jdbcTemplate.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(occurred_at))) * 1000, 0)
                FROM emporia_order_data.order_delivery_outbox
                WHERE status = 'IN_FLIGHT'
                """, (rs, row) -> rs.getLong(1)));
    }

    private int retentionDays() {
        return retentionDays;
    }

    private PendingOutput map(ResultSet rs, int row) throws SQLException {
        return new PendingOutput(rs.getLong("sequence_id"), new OrderDomainEvent(
                rs.getInt("schema_version"),
                rs.getObject("event_id", UUID.class),
                rs.getObject("command_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("user_subject"),
                rs.getString("desk_id"),
                rs.getString("event_type"),
                rs.getLong("order_version"),
                OrderStatus.valueOf(rs.getString("order_status")),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("payload")));
    }

    private record PendingOutput(long sequenceId, OrderDomainEvent event) { }
}
