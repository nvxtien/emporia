package com.emporia.execution;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconciliationReporterTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();

    /**
     * The distinction this whole class exists for: before anything has been
     * measured the gauges must not read zero, because zero is what a clean
     * system reports and "not checked yet" is not a clean system.
     */
    @Test
    void gaugesReadNotANumberUntilSomethingHasActuallyBeenChecked() {
        new ReconciliationReporter(mock(ReconciliationEndpoint.class), meters, Duration.ZERO);

        assertTrue(Double.isNaN(gauge("emporia.execution.reconciliation.missing")));
        assertTrue(Double.isNaN(gauge("emporia.execution.reconciliation.ghosts")));
        assertTrue(Double.isNaN(gauge("emporia.execution.reconciliation.age.seconds")),
                "age with no run is unknown, not zero seconds old");
    }

    @Test
    void publishesBothDirectionsAfterARun() {
        ReconciliationReporter reporter = reporterFor(new ReconciliationEndpoint.Report(
                true, "exchange-core", 96425, 2416, List.of("a", "b"), 61829, List.of("g1")));

        reporter.run("test");

        assertEquals(96425, gauge("emporia.execution.reconciliation.checked"));
        assertEquals(2416, gauge("emporia.execution.reconciliation.missing"));
        assertEquals(61829, gauge("emporia.execution.reconciliation.ghosts"));
        assertEquals(1, gauge("emporia.execution.reconciliation.supported"));
    }

    @Test
    void aVenueThatCannotEnumerateIsRecordedButIsNotAFinding() {
        ReconciliationReporter reporter = reporterFor(
                ReconciliationEndpoint.Report.unavailable("fix", 1200));

        reporter.run("test");

        assertEquals(0, gauge("emporia.execution.reconciliation.supported"));
        assertEquals(0, gauge("emporia.execution.reconciliation.missing"),
                "an unanswerable venue must not be reported as having lost orders");
        assertEquals(1200, gauge("emporia.execution.reconciliation.checked"));
    }

    /**
     * A failed run must not overwrite the last real measurement with zeros -
     * that would turn a reporting outage into an all-clear.
     */
    @Test
    void aFailedRunLeavesTheLastMeasurementStanding() {
        ReconciliationEndpoint endpoint = mock(ReconciliationEndpoint.class);
        when(endpoint.reconcile())
                .thenReturn(new ReconciliationEndpoint.Report(
                        true, "exchange-core", 10, 3, List.of("a"), 0, List.of()))
                .thenThrow(new IllegalStateException("venue unreachable"));
        ReconciliationReporter reporter = new ReconciliationReporter(endpoint, meters, Duration.ZERO);

        reporter.run("first");
        assertEquals(3, gauge("emporia.execution.reconciliation.missing"));

        reporter.run("second");
        assertEquals(3, gauge("emporia.execution.reconciliation.missing"),
                "a failed run must not read as zero missing");
    }

    @Test
    void ageBecomesMeasurableOnceARunCompletes() {
        reporterFor(new ReconciliationEndpoint.Report(
                true, "exchange-core", 5, 0, List.of(), 0, List.of())).run("test");

        double age = gauge("emporia.execution.reconciliation.age.seconds");
        assertFalse(Double.isNaN(age));
        assertTrue(age >= 0 && age < 60, "age was " + age);
    }

    private ReconciliationReporter reporterFor(ReconciliationEndpoint.Report report) {
        ReconciliationEndpoint endpoint = mock(ReconciliationEndpoint.class);
        when(endpoint.reconcile()).thenReturn(report);
        return new ReconciliationReporter(endpoint, meters, Duration.ZERO);
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }
}
