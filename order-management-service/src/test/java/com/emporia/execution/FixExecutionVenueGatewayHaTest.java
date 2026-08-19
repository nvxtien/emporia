package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.ha.LeaderElectionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FixExecutionVenueGatewayHaTest {
    private final RecordingCommands commands = new RecordingCommands();
    private final InMemoryFixSessionStateStore sessionState = new InMemoryFixSessionStateStore();
    private final InMemoryFixMessageLogStore messageLog = new InMemoryFixMessageLogStore();

    @Test
    void standbyAtBootRejectsRecoverUntilLeadershipPromotionArrives() {
        LeaderElectionService leaderElection = mock(LeaderElectionService.class);
        when(leaderElection.isPrimary()).thenReturn(false);
        FixExecutionVenueGateway standbyGateway = haGateway(leaderElection);
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));

        standbyGateway.start();
        try {
            assertThat(standbyGateway.orderIntakeReadiness().readyToAccept()).isFalse();
            assertThat(standbyGateway.orderIntakeReadiness().reason()).isEqualTo("not_primary");
            assertThatThrownBy(() -> standbyGateway.recover(order))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not active PRIMARY");

            standbyGateway.onLeadershipChange(new LeaderElectionService.LeadershipChangeEvent(
                    LeaderElectionService.NodeRole.PRIMARY, 2));
            when(leaderElection.isPrimary()).thenReturn(true);
            assertThat(standbyGateway.orderIntakeReadiness().readyToAccept()).isTrue();
            standbyGateway.recover(order);
        } finally {
            standbyGateway.stop();
        }
    }

    @Test
    void leadershipDemotionBlocksFurtherRecoverOperations() {
        LeaderElectionService leaderElection = mock(LeaderElectionService.class);
        when(leaderElection.isPrimary()).thenReturn(true);
        FixExecutionVenueGateway primaryGateway = haGateway(leaderElection);
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));

        primaryGateway.start();
        try {
            primaryGateway.recover(order);

            primaryGateway.onLeadershipChange(new LeaderElectionService.LeadershipChangeEvent(
                    LeaderElectionService.NodeRole.STANDBY, 3));

            when(leaderElection.isPrimary()).thenReturn(false);
            assertThat(primaryGateway.orderIntakeReadiness().readyToAccept()).isFalse();
            assertThat(primaryGateway.orderIntakeReadiness().reason()).isEqualTo("not_primary");
            assertThatThrownBy(() -> primaryGateway.recover(order))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not active PRIMARY");
        } finally {
            primaryGateway.stop();
        }
    }

    @Test
    void alreadyPrimaryAtBootAllowsRecoverImmediately() {
        LeaderElectionService leaderElection = mock(LeaderElectionService.class);
        when(leaderElection.isPrimary()).thenReturn(true);
        FixExecutionVenueGateway primaryGateway = haGateway(leaderElection);

        primaryGateway.start();
        try {
            assertThat(primaryGateway.orderIntakeReadiness().readyToAccept()).isTrue();
            primaryGateway.recover(sampleOrder(UUID.randomUUID(), new BigDecimal("150.00")));
        } finally {
            primaryGateway.stop();
        }
    }

    private FixExecutionVenueGateway haGateway(LeaderElectionService leaderElection) {
        return new FixExecutionVenueGateway(
                "XNAS=127.0.0.1:1:CLIENT:EXCHANGE",
                commands,
                sessionState,
                messageLog,
                false,
                "",
                "",
                "",
                "",
                "fix",
                Optional.of(leaderElection));
    }

    private static OrderView sampleOrder(UUID id, BigDecimal limitPrice) {
        ListingSnapshot listing = new ListingSnapshot(1L, 1, "AAPL", "Apple Inc", "AAPL", "XNAS", "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("150.00"), new BigDecimal("150.00"));
        return new OrderView(id, 1L, "user-1", "desk-1", listing,
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100"), limitPrice, new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO, OrderStatus.LIVE, OrderStatus.LIVE, "DMA", "orig-1",
                null, null, null, null, Instant.now(), Instant.now());
    }

    private static final class RecordingCommands extends ExecutionCommandPublisher {
        private RecordingCommands() {
            super(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        }
    }
}
