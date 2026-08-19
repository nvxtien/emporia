package com.emporia.execution;

import com.emporia.ordermanagement.OrderManagementServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the real Spring context - {@code SpringApplication.run}, not a slice -
 * and proves {@code ExecutionCommandPublisher}'s ring wiring actually resolved,
 * rather than trusting that it did.
 *
 * <p>Opt-in via the {@code ring-wiring-it} profile, composed with
 * {@code -Dmatching} the same way {@code postgres-it} composes with it (see
 * that profile's comment in this module's {@code pom.xml}): {@code mvn test
 * -Dmatching -Pring-wiring-it}. Both are required - {@code -Dmatching} for
 * {@code ExchangeCoreExecutionVenueGateway}/{@code RoutingExecutionVenueGateway}
 * to even be compiled (the default {@code agency} profile excludes them), the
 * profile for Surefire to pick up a {@code *Spec.java} file it does not
 * include by default. Named and gated exactly like
 * {@link com.emporia.ordermanagement.postgres.TradingOrderPostgresPersistenceSpec},
 * for the same reason: a real Postgres via Testcontainers, opt-in because not
 * every environment can run it.
 *
 * <h2>Why this exists</h2>
 * <p>LMAX_ARCHITECTURE_REWORK_PLAN.md task 5.1 changed
 * {@code ExecutionCommandPublisher}'s dependency from {@code ExecutionCommandHandler}
 * to {@code DisruptorOrderPipeline}, {@code @Lazy @Autowired(required = false)} -
 * the same defensive pattern this codebase was already burned by once this
 * session ({@code RoutingExecutionVenueGateway}'s own circular-dependency
 * incident). {@code required = false} means a broken wiring or an
 * unresolvable cycle does not fail context refresh; the app starts and
 * {@code publish()} only throws {@code IllegalStateException} the first time a
 * real fill/reject/venue-cancel arrives (task 5.1's own added null check).
 * Before this test the only verification of the real wiring was a one-off
 * manual restart-and-curl pass (Phase 1 checkpoint 6, recorded in the plan) -
 * true at the time, but nothing catches a regression introduced afterward.
 * This is that regression test.
 *
 * <p>Deliberately does not prove the wiring by reading
 * {@code ExecutionCommandPublisher.disruptorOrderPipeline} via reflection.
 * {@link #fill(UUID, String)} exercises the real failure mode instead: calling
 * the package-private {@code fill(...)} with an order id nothing in this
 * context has ever seen distinguishes exactly the two outcomes that matter -
 * {@code IllegalStateException} ("no OMS ring is configured") if the field
 * never resolved, versus {@code IllegalArgumentException} ("order was not
 * found") if the command reached the ring, was dispatched to the writer
 * thread, and {@code ExecutionCommandHandler.handle} ran and failed on
 * business grounds instead. Only the second is a passing wiring proof; the
 * first is exactly the bug this test exists to catch.
 *
 * <p>HA leader election is deliberately disabled here
 * ({@code emporia.ha.enabled=false}), not exercised: leadership is acquired
 * asynchronously via a {@code @Scheduled} task racing this same context's
 * {@code SmartLifecycle.start()} sweep (LMAX_ARCHITECTURE_REWORK_PLAN.md
 * Phase 1 review finding, axis 2 - a real bug, caught by restarting, since
 * reverted). Testing that timing is a different, not-yet-built test; asserting
 * ring wiring here would otherwise be flaky on unrelated grounds.
 */
@Testcontainers
@SpringBootTest(
        classes = OrderManagementServiceApplication.class,
        // Not NONE: SecurityConfig.securityFilterChain(HttpSecurity) needs a
        // (mock) web application context to get an HttpSecurity bean at all -
        // NONE drops the web context entirely and refresh fails on that
        // method's parameter before ever reaching the beans this test cares
        // about. MOCK gets a real bean graph without binding a real port.
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "emporia.ha.enabled=false",
                "emporia.execution.exchange-core.accounting-mode=matching-only",
                "emporia.execution.exchange-core.journaling=false",
                "emporia.execution.venue-mode=exchange-core",
                // WebEnvironment.MOCK binds no real port, so the default
                // http://localhost:8086 for this self-referential client
                // (used by ReconciliationReporter's startup reconciliation)
                // would otherwise reach whatever real process happens to be
                // listening on 8086 on the machine running this test - this
                // session's own dev OMS instance, observed while first
                // running this spec. Point it at a port nothing binds so
                // that call fails fast and cleanly instead.
                "emporia.services.order-management-url=http://localhost:1"
        }
)
// Without this, Spring only tears the context down via the JVM-exit shutdown
// hook it registers - by then exchange-core's embedded Chronicle library is
// racing that same shutdown-hook-processing phase to lazily register its own
// (BackgroundResourceReleaser), which java.lang.Runtime rejects
// ("Shutdown in progress") since hooks cannot register other hooks while
// running. Harmless (logged after the test's own assertions already passed)
// but hangs Surefire's fork for ~30s waiting on the JVM to exit. Forces
// context.close() - and so ExchangeCoreExecutionVenueGateway.stop()/
// venue.close() - to run promptly after this class's one test, well before
// the JVM's own shutdown sequence begins.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExecutionRingWiringSpec {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void wireInfrastructure(DynamicPropertyRegistry registry) throws IOException {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("emporia.execution.datasource.url", postgres::getJdbcUrl);
        registry.add("emporia.execution.datasource.username", postgres::getUsername);
        registry.add("emporia.execution.datasource.password", postgres::getPassword);

        // Both isolate this run from whatever real OMS instance may be up on
        // this machine (same default local-file paths this session has been
        // restarting against) and give every run of this spec a clean slate.
        Path storage = Files.createTempDirectory("execution-ring-wiring-exchange-core-");
        Path haLock = Files.createTempDirectory("execution-ring-wiring-ha-lock-").resolve("ha.lock");
        registry.add("emporia.execution.exchange-core.storage-directory", () -> storage.toString());
        registry.add("emporia.ha.lock-file-path", haLock::toString);
    }

    private final ExecutionCommandPublisher publisher;

    @Autowired
    ExecutionRingWiringSpec(ExecutionCommandPublisher publisher) {
        this.publisher = publisher;
    }

    @Test
    void executionCommandPublisherReachesTheRealRingAndTheHandlerRuns() {
        assertThatThrownBy(() -> fill(UUID.randomUUID(), "wiring-check-desk"))
                .as("a wired publisher reaches ExecutionCommandHandler and fails on business "
                        + "grounds (unknown order) - IllegalStateException here would mean "
                        + "disruptorOrderPipeline never resolved")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    private void fill(UUID orderId, String deskId) {
        publisher.fill(orderId, deskId, "wiring-check-ref-" + orderId,
                new BigDecimal("1"), new BigDecimal("1"), "WIRING-CHECK", Instant.now());
    }
}
