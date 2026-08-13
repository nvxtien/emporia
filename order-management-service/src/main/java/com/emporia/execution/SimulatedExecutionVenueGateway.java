package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
@ConditionalOnProperty(name = "emporia.execution.venue-mode", havingValue = "simulated", matchIfMissing = true)
class SimulatedExecutionVenueGateway implements ExecutionVenueGateway {
    private final ExecutionCommandPublisher commands;
    private final TaskScheduler scheduler;
    private final Duration fillDelay;
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    SimulatedExecutionVenueGateway(ExecutionCommandPublisher commands, TaskScheduler scheduler,
                                   @Value("${emporia.execution.fill-delay}") Duration fillDelay) {
        this.commands = commands;
        this.scheduler = scheduler;
        this.fillDelay = fillDelay;
    }

    @Override
    public void submit(OrderView order) {
        BigDecimal price = order.limitPrice() == null ? order.listing().referencePrice() : order.limitPrice();
        if (price == null || price.signum() <= 0) {
            throw new IllegalStateException("The execution venue has no valid reference price");
        }
        Instant executionTime = Instant.now().plus(fillDelay);
        ScheduledFuture<?> scheduled = scheduler.schedule(() -> {
            pending.remove(order.id());
            commands.fill(order.id(), order.deskId(), "SIM-" + order.id() + ":" + order.version(),
                    order.remainingQuantity(), price, order.listing().exchangeMic(), executionTime);
        }, executionTime);
        ScheduledFuture<?> previous = pending.put(order.id(), scheduled);
        if (previous != null) previous.cancel(false);
    }

    @Override
    public void modify(OrderView order) {
        submit(order);
    }

    @Override
    public void cancel(OrderView order) {
        ScheduledFuture<?> scheduled = pending.remove(order.id());
        if (scheduled != null) scheduled.cancel(false);
        commands.venueCancel(order.id(), order.deskId(),
                "SIM-CANCEL-" + order.id() + ":" + order.version(),
                order.listing().exchangeMic(), "Simulated venue confirmed cancellation");
    }

    @Override
    public void recover(OrderView order) {
        // Simulator state is intentionally ephemeral, so rescheduling the
        // deterministic remaining fill is its safe recovery operation.
        submit(order);
    }
}
