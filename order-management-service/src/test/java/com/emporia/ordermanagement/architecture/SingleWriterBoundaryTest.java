package com.emporia.ordermanagement.architecture;

import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.service.ExecutionCommandHandler;
import com.emporia.ordermanagement.service.OrderCommandHandler;
import com.emporia.ordermanagement.service.OrderCommandReplayHarness;
import com.emporia.ordermanagement.service.OrderShadowComparisonService;
import com.emporia.ordermanagement.service.OrderStateCache;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * LMAX_ARCHITECTURE_REWORK_PLAN.md task 5.5, and the rule it enforces: R1
 * (Single Writer ownership) - every OMS lifecycle mutation must be sequenced
 * through {@link DisruptorOrderPipeline}'s ring, not called or applied
 * directly by anything else. Static, not runtime: this catches a violation at
 * build time, before it ever reaches a running system - which is exactly the
 * gap that let the {@code RoutingExecutionVenueGateway} bean-wiring bug and
 * the {@code -Dmatching} profile incident (both this session) go undetected
 * until an actual restart.
 */
class SingleWriterBoundaryTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.emporia.ordermanagement", "com.emporia.execution");
    }

    /**
     * Two documented exceptions, both non-negotiable to keep this rule
     * meaningful rather than working around it:
     * {@link OrderCommandReplayHarness} calls the real handler directly
     * during startup WAL replay, before the ring's writer thread exists at
     * all - routing it through the ring would append every recovered command
     * to the log a second time (see the harness's own javadoc).
     * {@link OrderShadowComparisonService} constructs its own throwaway
     * {@code OrderCommandHandler} over an isolated, single-request
     * {@code OrderStateCache} purely for shadow verification - never the
     * shared production bean, never the shared cache - so it is not a second
     * writer of real OMS state even though it calls {@code handle(...)}
     * directly.
     */
    @Test
    void onlyTheRingAndDocumentedHarnessesCallOrderCommandHandlerDirectly() {
        ArchRule rule = noClasses()
                // doNotBelongToAnyOf, not areNotAssignableTo: OrderShadowComparisonService's
                // actual caller is its own nested ShadowSandbox class, which is not a Java
                // subtype of the outer class - belongToAnyOf is what treats a nested class
                // as part of its enclosing one.
                .that().doNotBelongToAnyOf(DisruptorOrderPipeline.class,
                        OrderCommandReplayHarness.class, OrderShadowComparisonService.class)
                .should().callMethod(OrderCommandHandler.class, "handle",
                        com.emporia.events.TradingEvents.OrderCommand.class)
                .because("LMAX_ARCHITECTURE_REWORK_PLAN.md R1 (Single Writer ownership): every OMS "
                        + "order-command mutation must be sequenced through DisruptorOrderPipeline. "
                        + "OrderCommandReplayHarness (pre-ring startup replay) and "
                        + "OrderShadowComparisonService (isolated sandbox instance, never the shared "
                        + "bean) are the two documented exceptions.");
        rule.check(classes);
    }

    /** Same rule for execution commands - no documented exception exists for this one. */
    @Test
    void onlyTheRingCallsExecutionCommandHandlerDirectly() {
        ArchRule rule = noClasses()
                .that().doNotBelongToAnyOf(DisruptorOrderPipeline.class)
                .should().callMethod(ExecutionCommandHandler.class, "handle",
                        com.emporia.events.TradingEvents.ExecutionCommand.class)
                .because("LMAX_ARCHITECTURE_REWORK_PLAN.md R1 (Single Writer ownership): every "
                        + "venue-originated mutation must be sequenced through "
                        + "DisruptorOrderPipeline.submitExecutionCommand, with no documented direct "
                        + "caller left (unlike OrderCommandHandler, which OrderCommandReplayHarness and "
                        + "OrderShadowComparisonService still call directly for documented reasons).");
        rule.check(classes);
    }

    /**
     * The 11 methods task 5.3's call-site audit found reached only from the
     * ring writer thread on the shared, live instance. {@code TradingOrder}
     * itself is excluded because several of these methods call each other
     * (e.g. {@code modify(BigDecimal,...)} calls {@code modify(long,...)}) -
     * same-class self-calls, not a second writer. {@link OrderStateCache} is
     * excluded too: {@code put(...)} is documented as "the one funnel every
     * committed state change passes through," which is precisely why it - and
     * only it - stamps {@code recordRevision()} rather than each mutator
     * doing so itself. It is not a second writer; the next rule
     * ({@link #onlyTheBlpHandlersWriteIntoTheOrderStateCache}) is what proves
     * only the BLP handlers can reach that funnel in the first place.
     */
    @Test
    void onlyTheBlpHandlersMutateTradingOrderLifecycleState() {
        Set<String> mutators = Set.of("modify", "applyFill", "requestCancel", "confirmCancel",
                "cancel", "recordRevision", "reject", "validateInvariants");
        ArchRule rule = noClasses()
                .that().doNotBelongToAnyOf(OrderCommandHandler.class, ExecutionCommandHandler.class,
                        TradingOrder.class, OrderStateCache.class)
                .should().callMethodWhere(callsMethodNamedOneOf(TradingOrder.class, mutators))
                .because("LMAX_ARCHITECTURE_REWORK_PLAN.md R1 (Single Writer ownership): only the BLP "
                        + "handlers, invoked from the ring, may mutate TradingOrder lifecycle state.");
        rule.check(classes);
    }

    /**
     * {@code put}/{@code putProcessed} are the two writes into the live-order
     * store, each also remembering into the dedup index as part of the same
     * call - not a separate write path, so not listed on its own.
     * {@code rememberExecutionReference} is: it mutates the execution-reference
     * dedup index independently of both (task 5.2), so without it here a new
     * caller from outside the BLP handlers could remember a reference the ring
     * never actually applied - a real second-writer gap this rule exists to
     * catch, found by review rather than by an actual violation (there is
     * none today; {@link com.emporia.ordermanagement.service.ExecutionCommandHandler}
     * is still the only caller). Everything else on {@link OrderStateCache} is
     * a read.
     */
    @Test
    void onlyTheBlpHandlersWriteIntoTheOrderStateCache() {
        Set<String> mutators = Set.of("put", "putProcessed", "rememberExecutionReference");
        ArchRule rule = noClasses()
                .that().doNotBelongToAnyOf(OrderCommandHandler.class, ExecutionCommandHandler.class,
                        OrderStateCache.class)
                .should().callMethodWhere(callsMethodNamedOneOf(OrderStateCache.class, mutators))
                .because("LMAX_ARCHITECTURE_REWORK_PLAN.md R1 (Single Writer ownership): only the BLP "
                        + "handlers, invoked from the ring, may write into OrderStateCache's live-order "
                        + "indexes.");
        rule.check(classes);
    }

    private static DescribedPredicate<JavaCall<?>> callsMethodNamedOneOf(Class<?> owner, Set<String> methodNames) {
        return DescribedPredicate.describe(
                "calls " + owner.getSimpleName() + "." + String.join("/", methodNames) + "(...)",
                call -> call.getTargetOwner().isEquivalentTo(owner) && methodNames.contains(call.getName()));
    }
}
