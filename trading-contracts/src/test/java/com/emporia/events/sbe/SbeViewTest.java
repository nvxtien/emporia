package com.emporia.events.sbe;

import com.emporia.events.TradingEvents.ExecutionCommand;
import com.emporia.events.TradingEvents.ExecutionCommandType;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.math.FixedPointMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SbeViewTest {

    // ── OrderDomainEvent round-trip ───────────────────────────────────────────

    @Test
    void orderDomainEvent_fixedFieldsReadableWithoutAllocation() {
        UUID eventId   = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID orderId   = UUID.randomUUID();
        Instant now    = Instant.now();

        OrderDomainEvent original = new OrderDomainEvent(
                1, eventId, commandId, orderId,
                "trader-sub-99", "DESK-A", "PARTIALLY_FILLED", 7L,
                OrderStatus.PARTIALLY_FILLED, now, "{\"v\":7}"
        );
        byte[] wire = SbeEncoderDecoder.encodeOrderDomainEvent(original);
        SbeView view = SbeView.ofOrderDomainEvent(wire);

        // Fixed-width fields — no allocation
        assertThat(view.msgType()).isEqualTo(SbeView.TYPE_ORDER_DOMAIN_EVENT);
        assertThat(view.schemaVersion()).isEqualTo(1);
        assertThat(view.orderVersion()).isEqualTo(7L);
        assertThat(view.orderStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(view.occurredAtMillis()).isEqualTo(now.toEpochMilli());

        // UUID long pairs — zero allocation
        assertThat(view.uuid0Hi()).isEqualTo(eventId.getMostSignificantBits());
        assertThat(view.uuid0Lo()).isEqualTo(eventId.getLeastSignificantBits());
        assertThat(view.uuid1Hi()).isEqualTo(commandId.getMostSignificantBits());
        assertThat(view.uuid1Lo()).isEqualTo(commandId.getLeastSignificantBits());
        assertThat(view.uuid2Hi()).isEqualTo(orderId.getMostSignificantBits());
        assertThat(view.uuid2Lo()).isEqualTo(orderId.getLeastSignificantBits());

        // Named typed helpers agree
        assertThat(view.eventIdHi()).isEqualTo(eventId.getMostSignificantBits());
        assertThat(view.eventIdLo()).isEqualTo(eventId.getLeastSignificantBits());
        assertThat(view.commandIdHi()).isEqualTo(commandId.getMostSignificantBits());
        assertThat(view.commandIdLo()).isEqualTo(commandId.getLeastSignificantBits());
        assertThat(view.orderIdHi()).isEqualTo(orderId.getMostSignificantBits());
        assertThat(view.orderIdLo()).isEqualTo(orderId.getLeastSignificantBits());

        // EC-only fields must be zero for ODE
        assertThat(view.quantityScaled()).isZero();
        assertThat(view.priceScaled()).isZero();
    }

    @Test
    void orderDomainEvent_uuidEqualityZeroAllocation() {
        UUID eventId = UUID.randomUUID();
        OrderDomainEvent original = new OrderDomainEvent(
                1, eventId, UUID.randomUUID(), UUID.randomUUID(),
                "sub", "desk", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}");
        SbeView view = SbeView.ofOrderDomainEvent(
                SbeEncoderDecoder.encodeOrderDomainEvent(original));

        // Hot-path identity check — no UUID object created
        assertThat(view.uuid0Equals(
                eventId.getMostSignificantBits(),
                eventId.getLeastSignificantBits())).isTrue();
        assertThat(view.uuid0Equals(0L, 0L)).isFalse();
    }

    @Test
    void orderDomainEvent_eventTypeIsZeroCopyDispatchKey() {
        OrderDomainEvent original = new OrderDomainEvent(
                1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "sub", "desk", "CANCEL_REQUESTED", 3L,
                OrderStatus.LIVE, Instant.now(), "{}"
        );
        SbeView view = SbeView.ofOrderDomainEvent(
                SbeEncoderDecoder.encodeOrderDomainEvent(original));

        // The hot-path dispatch pattern — pure byte compare, zero allocation
        AsciiView eventType = view.eventType();
        assertThat(eventType.equalsAscii("CANCEL_REQUESTED")).isTrue();
        assertThat(eventType.equalsAscii("CREATED")).isFalse();
        assertThat(eventType.isPresent()).isTrue();
    }

    @Test
    void orderDomainEvent_varViewsAreLazyCached() {
        OrderDomainEvent original = new OrderDomainEvent(
                1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "trader", "desk", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}"
        );
        SbeView view = SbeView.ofOrderDomainEvent(
                SbeEncoderDecoder.encodeOrderDomainEvent(original));

        AsciiView first  = view.eventType();
        AsciiView second = view.eventType();
        assertThat(first).isSameAs(second); // same instance — cached
    }

    @Test
    void orderDomainEvent_materializesToDomainRecord() {
        UUID eventId   = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID orderId   = UUID.randomUUID();
        Instant now    = Instant.now();

        OrderDomainEvent original = new OrderDomainEvent(
                1, eventId, commandId, orderId,
                "user-99", "DESK-B", "FILLED", 12L,
                OrderStatus.FILLED, now, "{\"status\":\"FILLED\"}"
        );
        byte[] wire = SbeEncoderDecoder.encodeOrderDomainEvent(original);
        SbeView view = SbeView.ofOrderDomainEvent(wire);
        OrderDomainEvent materialized = view.toOrderDomainEvent();

        assertThat(materialized.eventId()).isEqualTo(eventId);
        assertThat(materialized.commandId()).isEqualTo(commandId);
        assertThat(materialized.orderId()).isEqualTo(orderId);
        assertThat(materialized.userSubject()).isEqualTo("user-99");
        assertThat(materialized.deskId()).isEqualTo("DESK-B");
        assertThat(materialized.eventType()).isEqualTo("FILLED");
        assertThat(materialized.orderVersion()).isEqualTo(12L);
        assertThat(materialized.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(materialized.occurredAt().toEpochMilli()).isEqualTo(now.toEpochMilli());
        assertThat(materialized.payload()).isEqualTo("{\"status\":\"FILLED\"}");
        // long pairs must agree with the materialised UUIDs
        assertThat(view.toUUID0()).isEqualTo(eventId);
        assertThat(view.toUUID1()).isEqualTo(commandId);
        assertThat(view.toUUID2()).isEqualTo(orderId);
    }

    @Test
    void orderDomainEvent_wrongTypeAccessorThrows() {
        byte[] wire = SbeEncoderDecoder.encodeOrderDomainEvent(new OrderDomainEvent(
                1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "u", "d", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}"));
        SbeView view = SbeView.ofOrderDomainEvent(wire);

        // EC-only accessors must throw on an ODE view
        assertThatThrownBy(view::executionCommandType)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only valid for ExecutionCommand");
        assertThatThrownBy(view::executionReference)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::venue)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::detail)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::toExecutionCommand)
                .isInstanceOf(IllegalStateException.class);
    }

    // ── ExecutionCommand round-trip ───────────────────────────────────────────

    @Test
    void executionCommand_fixedFieldsReadableWithoutAllocation() {
        UUID commandId = UUID.randomUUID();
        UUID orderId   = UUID.randomUUID();
        Instant now    = Instant.now();

        ExecutionCommand original = new ExecutionCommand(
                1, commandId, ExecutionCommandType.FILL,
                orderId, "DESK-C", "ref-fill-001",
                new BigDecimal("25.000000"), new BigDecimal("150.750000"),
                "XNAS", now, "partial fill"
        );
        byte[] wire = SbeEncoderDecoder.encodeExecutionCommand(original);
        SbeView view = SbeView.ofExecutionCommand(wire);

        // Fixed-width fields
        assertThat(view.msgType()).isEqualTo(SbeView.TYPE_EXECUTION_COMMAND);
        assertThat(view.executionCommandType()).isEqualTo(ExecutionCommandType.FILL);
        assertThat(view.occurredAtMillis()).isEqualTo(now.toEpochMilli());

        // UUID long pairs — zero allocation
        assertThat(view.uuid0Hi()).isEqualTo(commandId.getMostSignificantBits());
        assertThat(view.uuid0Lo()).isEqualTo(commandId.getLeastSignificantBits());
        assertThat(view.uuid1Hi()).isEqualTo(orderId.getMostSignificantBits());
        assertThat(view.uuid1Lo()).isEqualTo(orderId.getLeastSignificantBits());
        assertThat(view.uuid2Hi()).isZero();
        assertThat(view.uuid2Lo()).isZero();

        // Named EC typed helpers agree
        assertThat(view.ecCommandIdHi()).isEqualTo(commandId.getMostSignificantBits());
        assertThat(view.ecCommandIdLo()).isEqualTo(commandId.getLeastSignificantBits());
        assertThat(view.ecOrderIdHi()).isEqualTo(orderId.getMostSignificantBits());
        assertThat(view.ecOrderIdLo()).isEqualTo(orderId.getLeastSignificantBits());

        // Fixed-point numeric fields — zero allocation
        assertThat(view.quantityScaled()).isEqualTo(
                FixedPointMath.toScaledLong(new BigDecimal("25.000000")));
        assertThat(view.priceScaled()).isEqualTo(
                FixedPointMath.toScaledLong(new BigDecimal("150.750000")));

        // ODE-only field must be zero
        assertThat(view.orderVersion()).isZero();
    }

    @Test
    void executionCommand_uuidEqualityZeroAllocation() {
        UUID commandId = UUID.randomUUID();
        UUID orderId   = UUID.randomUUID();
        ExecutionCommand original = new ExecutionCommand(
                1, commandId, ExecutionCommandType.CANCEL,
                orderId, "d", "r", null, null, "XNAS", Instant.now(), "cancel");
        SbeView view = SbeView.ofExecutionCommand(
                SbeEncoderDecoder.encodeExecutionCommand(original));

        // Hot-path correlation — no UUID object needed
        assertThat(view.uuid0Equals(
                commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits())).isTrue();
        assertThat(view.uuid1Equals(
                orderId.getMostSignificantBits(),
                orderId.getLeastSignificantBits())).isTrue();
        // Slot 2 absent in EC
        assertThat(view.toUUID2()).isNull();
    }

    @Test
    void executionCommand_varViewsPointIntoOriginalBuffer() {
        UUID commandId = UUID.randomUUID();
        UUID orderId   = UUID.randomUUID();
        ExecutionCommand original = new ExecutionCommand(
                1, commandId, ExecutionCommandType.CANCEL,
                orderId, "DESK-D", "ref-cancel-002",
                null, null, "XNYS", Instant.now(), "user cancel"
        );
        byte[] wire = SbeEncoderDecoder.encodeExecutionCommand(original);
        SbeView view = SbeView.ofExecutionCommand(wire);

        // Zero-allocation checks — no String materialised
        assertThat(view.deskId().equalsAscii("DESK-D")).isTrue();
        assertThat(view.executionReference().equalsAscii("ref-cancel-002")).isTrue();
        assertThat(view.venue().equalsAscii("XNYS")).isTrue();
        assertThat(view.detail().equalsAscii("user cancel")).isTrue();

        // Null numeric fields encode as 0L → decode as 0L
        assertThat(view.quantityScaled()).isZero();
        assertThat(view.priceScaled()).isZero();
    }

    @Test
    void executionCommand_materializesToDomainRecord() {
        UUID commandId = UUID.randomUUID();
        UUID orderId   = UUID.randomUUID();
        Instant now    = Instant.now();

        ExecutionCommand original = new ExecutionCommand(
                1, commandId, ExecutionCommandType.REJECT,
                orderId, "DESK-E", "ref-reject-003",
                null, null, "XNAS", now, "no liquidity"
        );
        byte[] wire = SbeEncoderDecoder.encodeExecutionCommand(original);
        ExecutionCommand materialized = SbeView.ofExecutionCommand(wire).toExecutionCommand();

        assertThat(materialized.commandId()).isEqualTo(commandId);
        assertThat(materialized.orderId()).isEqualTo(orderId);
        assertThat(materialized.commandType()).isEqualTo(ExecutionCommandType.REJECT);
        assertThat(materialized.deskId()).isEqualTo("DESK-E");
        assertThat(materialized.executionReference()).isEqualTo("ref-reject-003");
        assertThat(materialized.quantity()).isNull();
        assertThat(materialized.price()).isNull();
        assertThat(materialized.venue()).isEqualTo("XNAS");
        assertThat(materialized.detail()).isEqualTo("no liquidity");
        assertThat(materialized.occurredAt().toEpochMilli()).isEqualTo(now.toEpochMilli());
    }

    @Test
    void executionCommand_wrongTypeAccessorThrows() {
        byte[] wire = SbeEncoderDecoder.encodeExecutionCommand(new ExecutionCommand(
                1, UUID.randomUUID(), ExecutionCommandType.FILL,
                UUID.randomUUID(), "d", "r",
                BigDecimal.ONE, BigDecimal.TEN, "XNAS", Instant.now(), "ok"));
        SbeView view = SbeView.ofExecutionCommand(wire);

        assertThatThrownBy(view::orderStatus)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::eventType)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(view::toOrderDomainEvent)
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Guard: wrong message type in buffer ───────────────────────────────────

    @Test
    void ofOrderDomainEvent_rejectsExecutionCommandBuffer() {
        byte[] ecWire = SbeEncoderDecoder.encodeExecutionCommand(new ExecutionCommand(
                1, UUID.randomUUID(), ExecutionCommandType.FILL,
                UUID.randomUUID(), "d", "r",
                BigDecimal.ONE, BigDecimal.TEN, "XNAS", Instant.now(), "ok"));
        assertThatThrownBy(() -> SbeView.ofOrderDomainEvent(ecWire))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected msgType");
    }

    @Test
    void ofExecutionCommand_rejectsOrderDomainEventBuffer() {
        byte[] odeWire = SbeEncoderDecoder.encodeOrderDomainEvent(new OrderDomainEvent(
                1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "u", "d", "CREATED", 1L, OrderStatus.LIVE, Instant.now(), "{}"));
        assertThatThrownBy(() -> SbeView.ofExecutionCommand(odeWire))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected msgType");
    }

    @Test
    void nullAndEmptyBufferRejected() {
        assertThatThrownBy(() -> SbeView.ofOrderDomainEvent(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SbeView.ofOrderDomainEvent(new byte[3]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Correctness: view and materialized record agree ───────────────────────

    @Test
    void viewAndMaterializedRecordAgreeOnAllFields_ode() {
        UUID eventId   = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID orderId   = UUID.randomUUID();
        Instant now    = Instant.now();
        OrderDomainEvent original = new OrderDomainEvent(
                1, eventId, commandId, orderId,
                "subject-42", "DESK-F", "MODIFIED", 5L,
                OrderStatus.LIVE, now, "{\"qty\":\"5\"}"
        );
        byte[] wire = SbeEncoderDecoder.encodeOrderDomainEvent(original);
        SbeView view = SbeView.ofOrderDomainEvent(wire);
        OrderDomainEvent materialized = view.toOrderDomainEvent();

        // Long-pair accessors agree with materialised UUIDs
        assertThat(view.toUUID0()).isEqualTo(materialized.eventId());
        assertThat(view.toUUID1()).isEqualTo(materialized.commandId());
        assertThat(view.toUUID2()).isEqualTo(materialized.orderId());
        // Raw long pairs encode the same bits
        assertThat(view.uuid0Hi()).isEqualTo(materialized.eventId().getMostSignificantBits());
        assertThat(view.uuid0Lo()).isEqualTo(materialized.eventId().getLeastSignificantBits());
        // Var fields
        assertThat(view.userSubject().toString()).isEqualTo(materialized.userSubject());
        assertThat(view.deskId().toString()).isEqualTo(materialized.deskId());
        assertThat(view.eventType().toString()).isEqualTo(materialized.eventType());
        assertThat(view.orderVersion()).isEqualTo(materialized.orderVersion());
        assertThat(view.orderStatus()).isEqualTo(materialized.status());
        assertThat(view.occurredAtMillis()).isEqualTo(materialized.occurredAt().toEpochMilli());
        assertThat(view.payload().toString()).isEqualTo(materialized.payload());
    }

    @Test
    void viewAndMaterializedRecordAgreeOnAllFields_ec() {
        UUID commandId = UUID.randomUUID();
        UUID orderId   = UUID.randomUUID();
        Instant now    = Instant.now();
        BigDecimal qty   = new BigDecimal("10.500000");
        BigDecimal price = new BigDecimal("200.250000");
        ExecutionCommand original = new ExecutionCommand(
                1, commandId, ExecutionCommandType.FILL,
                orderId, "DESK-G", "ref-fill-xyz",
                qty, price, "XNYS", now, "full fill"
        );
        byte[] wire = SbeEncoderDecoder.encodeExecutionCommand(original);
        SbeView view = SbeView.ofExecutionCommand(wire);
        ExecutionCommand materialized = view.toExecutionCommand();

        // Long-pair accessors agree with materialised UUIDs
        assertThat(view.toUUID0()).isEqualTo(materialized.commandId());
        assertThat(view.toUUID1()).isEqualTo(materialized.orderId());
        assertThat(view.toUUID2()).isNull();
        // Raw long pairs
        assertThat(view.uuid0Hi()).isEqualTo(materialized.commandId().getMostSignificantBits());
        assertThat(view.uuid1Lo()).isEqualTo(materialized.orderId().getLeastSignificantBits());
        // Other fields
        assertThat(view.executionCommandType()).isEqualTo(materialized.commandType());
        assertThat(view.deskId().toString()).isEqualTo(materialized.deskId());
        assertThat(view.executionReference().toString()).isEqualTo(materialized.executionReference());
        assertThat(FixedPointMath.toBigDecimal(view.quantityScaled()))
                .isEqualByComparingTo(materialized.quantity());
        assertThat(FixedPointMath.toBigDecimal(view.priceScaled()))
                .isEqualByComparingTo(materialized.price());
        assertThat(view.venue().toString()).isEqualTo(materialized.venue());
        assertThat(view.detail().toString()).isEqualTo(materialized.detail());
        assertThat(view.occurredAtMillis()).isEqualTo(materialized.occurredAt().toEpochMilli());
    }
}
