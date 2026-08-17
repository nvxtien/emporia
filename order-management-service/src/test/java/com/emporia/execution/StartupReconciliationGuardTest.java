package com.emporia.execution;

import com.emporia.ordermanagement.service.LiveDirectOrders;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class StartupReconciliationGuardTest {

    @Test
    void refusesToOpenWhenTheVenueIsMissingOrdersAndPolicyIsRefuse() {
        StartupReconciliationGuard guard = guard("refuse", 0, report(true, 100, 7, 0));

        IllegalStateException refusal = assertThrows(IllegalStateException.class, guard::start);
        assertTrue(refusal.getMessage().contains("missing 7 of 100"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("startup-policy=warn"),
                "a refusal must say how to override it");
    }

    @Test
    void opensAnywayUnderTheDefaultPolicy() {
        assertDoesNotThrow(() -> guard("warn", 0, report(true, 100, 7, 0)).start());
    }

    /**
     * A guard that trips on a single in-flight cancellation gets switched off,
     * and then protects nothing.
     */
    @Test
    void toleratesUpToTheConfiguredThreshold() {
        assertDoesNotThrow(() -> guard("refuse", 10, report(true, 100, 10, 0)).start());
        assertThrows(IllegalStateException.class, () -> guard("refuse", 10, report(true, 100, 11, 0)).start());
    }

    /** Ghosts are serious, but refusing on them makes recovery impossible. */
    @Test
    void neverRefusesOnGhostsAlone() {
        assertDoesNotThrow(() -> guard("refuse", 0, report(true, 100, 0, 61829)).start());
    }

    /** Not being able to check is a different condition from a disagreement. */
    @Test
    void doesNotRefuseWhenTheCheckItselfFails() {
        ReconciliationEndpoint endpoint = mock(ReconciliationEndpoint.class);
        when(endpoint.reconcile(anyList())).thenThrow(new IllegalStateException("venue unreachable"));
        LiveDirectOrders live = mock(LiveDirectOrders.class);
        when(live.current()).thenReturn(List.of());

        assertDoesNotThrow(() -> new StartupReconciliationGuard(endpoint, live, "refuse", 0).start());
    }

    @Test
    void doesNotRefuseWhenTheVenueCannotEnumerate() {
        assertDoesNotThrow(() -> guard("refuse", 0,
                ReconciliationEndpoint.Report.unavailable("fix", 100)).start());
    }

    /** It has to run before the web server binds, or it is a report, not a guard. */
    @Test
    void startsAfterTheVenueAndBeforeTheWebServer() {
        int phase = guard("warn", 0, report(true, 1, 0, 0)).getPhase();
        assertTrue(phase > Integer.MAX_VALUE - 3072, "must start after the venue gateway");
        // The framework's own constant, not a guess. The first version of this
        // test asserted against MAX_VALUE - 1 and passed while the guard ran
        // twenty-four seconds after the port opened.
        assertTrue(phase < 2147481599, "must start before the web server (MAX_VALUE - 2048)");
    }

    private static ReconciliationEndpoint.Report report(boolean supported, int checked,
                                                        int missing, int ghosts) {
        return new ReconciliationEndpoint.Report(supported, "exchange-core", checked,
                missing, List.of(), ghosts, List.of());
    }

    private static StartupReconciliationGuard guard(String policy, int threshold,
                                                    ReconciliationEndpoint.Report report) {
        ReconciliationEndpoint endpoint = mock(ReconciliationEndpoint.class);
        when(endpoint.reconcile(anyList())).thenReturn(report);
        LiveDirectOrders live = mock(LiveDirectOrders.class);
        when(live.current()).thenReturn(List.of());
        return new StartupReconciliationGuard(endpoint, live, policy, threshold);
    }
}
