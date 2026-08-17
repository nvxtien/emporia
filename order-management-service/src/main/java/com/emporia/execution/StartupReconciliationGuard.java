package com.emporia.execution;

import com.emporia.ordermanagement.service.LiveDirectOrders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Compares order-management's live set against the venue's book <b>before the
 * port opens</b>, so a disagreement is known before orders are accepted rather
 * than after.
 *
 * <h2>Why this exists alongside {@link ReconciliationReporter}</h2>
 * <p>The reporter runs on {@code ApplicationReadyEvent} and is therefore a
 * report: by the time it answers, the service is already trading. That was the
 * cheap half, and it did its job - it is how the numbers below stopped being
 * invisible. This is the half that can act.
 *
 * <h2>Phase</h2>
 * <p>Between the venue gateway ({@code Integer.MAX_VALUE - 1024}, which starts
 * the engine and rebuilds its lifecycle projection) and Spring Boot's web
 * server ({@code Integer.MAX_VALUE - 1}). Earlier and the venue cannot answer;
 * later and the port is already accepting orders.
 *
 * <h2>Policy, and why the default is not to refuse</h2>
 * <p>Missing orders are a correctness failure: order-management holds them,
 * the venue does not, they were acknowledged 201, and they cannot fill.
 * Refusing to start on them is defensible and is available.
 *
 * <p>It is not the default, because refusing makes the service un-startable in
 * exactly the situation where somebody is trying to recover it, and the same
 * measurement that justifies the check also shows the condition is reachable by
 * an ordinary hard kill. Knowing before the first order is accepted is most of
 * the value; refusing is a deployment decision on top of it.
 *
 * <p>Ghosts never refuse. The venue holding orders order-management has
 * finished with is serious, but a service that will not start until somebody
 * clears 61,829 of them by hand is worse than one that says so loudly.
 */
@Component
class StartupReconciliationGuard implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciliationGuard.class);

    private final ReconciliationEndpoint reconciliation;
    private final LiveDirectOrders liveDirectOrders;
    private final boolean refuse;
    private final int missingThreshold;
    private volatile boolean running;

    StartupReconciliationGuard(
            ReconciliationEndpoint reconciliation,
            LiveDirectOrders liveDirectOrders,
            @Value("${emporia.execution.reconciliation.startup-policy:warn}") String policy,
            // Not zero by accident. A cancellation in flight when the process
            // stopped can leave a small count behind on an otherwise healthy
            // restart, and a guard that trips on one order would be switched off
            // within a week - at which point it protects nothing.
            @Value("${emporia.execution.reconciliation.missing-threshold:0}") int missingThreshold) {
        this.reconciliation = reconciliation;
        this.liveDirectOrders = liveDirectOrders;
        this.refuse = "refuse".equalsIgnoreCase(policy);
        this.missingThreshold = missingThreshold;
    }

    @Override
    public void start() {
        running = true;
        ReconciliationEndpoint.Report report;
        try {
            report = reconciliation.reconcile(liveDirectOrders.current());
        } catch (RuntimeException failure) {
            // Never refuse on a failure to check. Not being able to reach the
            // venue is a different condition from the venue disagreeing, and
            // treating them alike would turn every transient error into an
            // outage.
            log.error("Startup reconciliation could not run; the venue and order-management "
                    + "are unchecked, not in agreement", failure);
            return;
        }

        if (!report.supported()) {
            log.info("Startup reconciliation skipped: venue {} cannot enumerate open orders",
                    report.venueMode());
            return;
        }
        if (report.ghostCount() > 0) {
            log.error("Startup reconciliation: venue {} is holding {} order(s) order-management "
                            + "has no live record of. They can still match.",
                    report.venueMode(), report.ghostCount());
        }
        if (report.missingCount() <= missingThreshold) {
            log.info("Startup reconciliation: {} live order(s) checked against venue {}, {} missing",
                    report.ordersChecked(), report.venueMode(), report.missingCount());
            return;
        }

        String detail = String.format(
                "venue %s is missing %d of %d live order(s). They were acknowledged to clients, "
                        + "they are durable here, and the venue cannot fill them.",
                report.venueMode(), report.missingCount(), report.ordersChecked());
        if (refuse) {
            throw new IllegalStateException("Refusing to open for trading: " + detail
                    + " Set emporia.execution.reconciliation.startup-policy=warn to start anyway.");
        }
        log.error("Startup reconciliation: {} Opening for trading anyway because "
                + "startup-policy=warn.", detail);
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** After the venue gateway has started, before the web server binds. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 2560;
    }
}
