package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class RoutingExecutionVenueGatewayTest {

    private static ExecutionVenueGateway gateway(String mode) {
        ExecutionVenueGateway gateway = mock(ExecutionVenueGateway.class);
        when(gateway.venueMode()).thenReturn(mode);
        return gateway;
    }

    private static OrderView anOrder() {
        return mock(OrderView.class);
    }

    /**
     * Phase 0 changes nothing: with both gateways present, orders go where the
     * configured mode says, which is what {@code @ConditionalOnProperty} used to
     * decide by leaving the other bean out of the context entirely.
     */
    @Test
    void routesToTheConfiguredModeAndLeavesTheOtherAlone() {
        ExecutionVenueGateway internal = gateway("exchange-core");
        ExecutionVenueGateway external = gateway("fix");
        OrderView order = anOrder();

        RoutingExecutionVenueGateway router =
                new RoutingExecutionVenueGateway(List.of(internal, external), "exchange-core");
        router.submit(order);
        router.modify(order);
        router.cancel(order);
        router.recover(order);

        verify(internal).submit(order);
        verify(internal).modify(order);
        verify(internal).cancel(order);
        verify(internal).recover(order);
        // Not verifyNoInteractions: the router asks every gateway for its
        // venueMode() while building its index, so the unselected one has been
        // touched. What matters is that no order reached it.
        verify(external, never()).submit(order);
        verify(external, never()).modify(order);
        verify(external, never()).cancel(order);
        verify(external, never()).recover(order);
    }

    @Test
    void theOtherGatewayIsStillReachableForTheWorkThatNeedsIt() {
        ExecutionVenueGateway internal = gateway("exchange-core");
        ExecutionVenueGateway external = gateway("fix");

        RoutingExecutionVenueGateway router =
                new RoutingExecutionVenueGateway(List.of(internal, external), "exchange-core");

        assertThat(router.forMode("fix")).isSameAs(external);
        assertThat(router.forMode("exchange-core")).isSameAs(internal);
        assertThat(router.forMode("simulated")).isNull();
    }

    /**
     * A mode with no gateway must not start. The alternative is a service that
     * accepts orders and sends them nowhere, which looks healthy from outside
     * and loses every order it takes.
     */
    @Test
    void refusesToStartWhenTheConfiguredModeHasNoGateway() {
        assertThatThrownBy(() -> new RoutingExecutionVenueGateway(List.of(gateway("fix")), "exchange-core"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no gateway")
                .hasMessageContaining("[fix]");
    }

    /**
     * The agency artifact, the default build, has no internalising gateway, so
     * the list simply has one fewer element - no special case, which is why the
     * mode is declared by each gateway rather than looked up by bean name.
     */
    @Test
    void theArtifactWithoutTheInternalisingGatewayStillRoutesExternally() {
        ExecutionVenueGateway external = gateway("fix");
        OrderView order = anOrder();

        RoutingExecutionVenueGateway router =
                new RoutingExecutionVenueGateway(List.of(external), "fix");
        router.submit(order);

        verify(external).submit(order);
        assertThat(router.forMode("exchange-core")).isNull();
    }

    @Test
    void theConfiguredModeIsReadLeniently() {
        ExecutionVenueGateway internal = gateway("exchange-core");

        RoutingExecutionVenueGateway router =
                new RoutingExecutionVenueGateway(List.of(internal), "  Exchange-Core  ");

        assertThat(router.venueMode()).isEqualTo("exchange-core");
    }

    /**
     * Spring already excludes a bean from a collection injected into it, but the
     * router filters explicitly so that its correctness is a property of this
     * code rather than of the framework's autowiring rules.
     */
    @Test
    void doesNotRouteToItself() {
        ExecutionVenueGateway internal = gateway("exchange-core");
        RoutingExecutionVenueGateway inner =
                new RoutingExecutionVenueGateway(List.of(internal), "exchange-core");

        RoutingExecutionVenueGateway outer =
                new RoutingExecutionVenueGateway(List.of(inner, internal), "exchange-core");

        assertThat(outer.forMode("exchange-core")).isSameAs(internal);
    }
}
