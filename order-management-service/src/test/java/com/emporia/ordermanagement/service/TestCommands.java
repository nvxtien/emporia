package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.ordermanagement.dto.ProcessingOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

final class TestCommands {
    private TestCommands() {
    }

    static OrderCommand command(UUID commandId) {
        UUID orderId = UUID.randomUUID();
        return new OrderCommand(
                SCHEMA_VERSION, commandId, CommandType.CREATE,
                "trader", Instant.EPOCH, orderId, null, listing(),
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100"), "DMA", "ref", null, Map.of()
        );
    }

    static ProcessingOutcome outcome(UUID commandId) {
        OrderCommandResult result = new OrderCommandResult(SCHEMA_VERSION, commandId, true, 201, null, "{}");
        return new ProcessingOutcome(result, List.of());
    }

    private static ListingSnapshot listing() {
        return new ListingSnapshot(
                1, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "Nasdaq",
                "US", "USD", new BigDecimal("0.01"), new BigDecimal("0.01"),
                new BigDecimal("200"), new BigDecimal("198")
        );
    }
}