package com.emporia.execution;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link ReconciliationEndpoint} without waiting for an operator to think
 * of it, and publishes what it finds.
 *
 * <h2>Why this exists</h2>
 * <p>The comparison was already written and already correct. Nothing called it
 * outside its own tests, so it could only ever answer a question somebody
 * already suspected - and the failure it detects is specifically the one nobody
 * suspects, because every component involved looks healthy from the inside.
 *
 * <p>Measured 2026-08-17 on a stack that reported itself entirely well: 61,829
 * orders resting on the venue that order-management had finished with, and -
 * after a hard kill under load - 2,416 orders order-management held as LIVE that
 * the matching engine had lost. Both sides were internally consistent. The
 * clients had their 201s. See CONFIGURATION.md, "The venue's journal does not
 * recover process death".
 *
 * <h2>On ApplicationReadyEvent, not @PostConstruct</h2>
 * <p>{@code TradingDataClient.recoverable()} is an HTTP call this process makes
 * to itself, so the port has to be open before it can succeed. That is a real
 * constraint and it makes this a <b>report, not a guard</b>: orders are already
 * being accepted by the time it answers. Moving the check in front of traffic
 * means reading the live set directly out of {@code OrderStateCache} instead,
 * which is the next phase and a larger change.
 */
@Component
class ReconciliationReporter {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationReporter.class);

    private final ReconciliationEndpoint reconciliation;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    // NaN until something has actually been measured. A gauge that reads 0
    // before its first run is indistinguishable from a gauge reporting a clean
    // system, and "not checked yet" is not "nothing wrong" - which is the whole
    // failure this class exists to stop.
    private volatile double checked = Double.NaN;
    private volatile double missing = Double.NaN;
    private volatile double ghosts = Double.NaN;
    private volatile double supported = Double.NaN;
    private volatile long lastRunEpochMs;

    ReconciliationReporter(ReconciliationEndpoint reconciliation,
                           MeterRegistry meters,
                           @Value("${emporia.execution.reconciliation.interval:0s}") Duration interval) {
        this.reconciliation = reconciliation;
        this.interval = interval == null ? Duration.ZERO : interval;

        Gauge.builder("emporia.execution.reconciliation.checked", this, r -> r.checked)
                .description("Live orders order-management asked the venue about")
                .register(meters);
        Gauge.builder("emporia.execution.reconciliation.missing", this, r -> r.missing)
                .description("Orders order-management holds as live that the venue does not have")
                .register(meters);
        Gauge.builder("emporia.execution.reconciliation.ghosts", this, r -> r.ghosts)
                .description("Orders resting on the venue that order-management has no live record of")
                .register(meters);
        Gauge.builder("emporia.execution.reconciliation.supported", this, r -> r.supported)
                .description("1 when the venue can enumerate its open orders, 0 when it cannot")
                .register(meters);
        Gauge.builder("emporia.execution.reconciliation.age.seconds", this,
                        r -> r.lastRunEpochMs == 0 ? Double.NaN
                                : (System.currentTimeMillis() - r.lastRunEpochMs) / 1000.0)
                .description("Seconds since reconciliation last completed")
                .register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        // Off the event thread. This takes twelve seconds at ~96,000 live orders
        // and holds a database connection for the whole of it; holding up
        // readiness for a report would trade an availability problem for an
        // observability one.
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "reconciliation-reporter");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.execute(() -> run("startup"));
        if (interval.toSeconds() > 0) {
            scheduler.scheduleWithFixedDelay(() -> run("periodic"),
                    interval.toSeconds(), interval.toSeconds(), TimeUnit.SECONDS);
            log.info("Reconciliation will also run every {}s", interval.toSeconds());
        }
    }

    void run(String trigger) {
        try {
            ReconciliationEndpoint.Report report = reconciliation.reconcile();
            checked = report.ordersChecked();
            missing = report.missingCount();
            ghosts = report.ghostCount();
            supported = report.supported() ? 1 : 0;
            lastRunEpochMs = System.currentTimeMillis();
            report(trigger, report);
        } catch (RuntimeException failure) {
            // Never fatal, and the gauges are deliberately left as they were:
            // overwriting a real measurement with zeros because the next run
            // failed would turn a reporting outage into a false all-clear.
            log.error("Reconciliation ({}) failed; the venue and order-management are "
                    + "unchecked, not in agreement", trigger, failure);
        }
    }

    private void report(String trigger, ReconciliationEndpoint.Report report) {
        if (!report.supported()) {
            // Not a finding. VenueOpenOrders.unsupported() exists precisely so a
            // gateway that cannot enumerate says so, rather than having its
            // silence read as "every order is missing". The gauge records it; no
            // alert fires on it.
            log.info("Reconciliation ({}) skipped: venue {} cannot enumerate open orders",
                    trigger, report.venueMode());
            return;
        }
        if (report.missingCount() == 0 && report.ghostCount() == 0) {
            log.info("Reconciliation ({}) clean: {} live order(s) all present on venue {}",
                    trigger, report.ordersChecked(), report.venueMode());
            return;
        }
        // The two directions are logged separately and named, because they have
        // different causes and different remedies. A single "reconciliation
        // failed" would erase the only diagnostic the report carries.
        if (report.missingCount() > 0) {
            log.error("Reconciliation ({}): venue {} is MISSING {} of {} live order(s) - "
                            + "order-management holds them, the venue does not, and they cannot fill. "
                            + "First few: {}",
                    trigger, report.venueMode(), report.missingCount(), report.ordersChecked(),
                    firstFew(report.missingOrders()));
        }
        if (report.ghostCount() > 0) {
            log.error("Reconciliation ({}): venue {} is holding {} GHOST order(s) "
                            + "order-management has no live record of; they can still match. "
                            + "First few: {}",
                    trigger, report.venueMode(), report.ghostCount(), firstFew(report.ghostOrders()));
        }
    }

    private static List<String> firstFew(List<String> all) {
        return all.size() <= 5 ? all : all.subList(0, 5);
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
