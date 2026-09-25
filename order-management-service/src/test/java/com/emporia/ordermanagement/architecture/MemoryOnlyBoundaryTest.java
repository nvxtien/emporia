package com.emporia.ordermanagement.architecture;

import com.emporia.ordermanagement.service.ExecutionCommandHandler;
import com.emporia.ordermanagement.service.OrderCommandHandler;
import com.emporia.ordermanagement.service.OrderStateCache;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * LMAX_ARCHITECTURE_REWORK_PLAN.md task 7.3, the rule it enforces: R2 (BLP is
 * memory-only) - {@link OrderStateCache#findByIdAndDeskId} falls through to
 * {@code TradingOrderRepository} on a cache miss, which is fine for a general
 * cache consumer and not for the single writer thread. Task 7 gave the BLP
 * handlers {@link OrderStateCache#findLiveByIdAndDeskMemoryOnly}, which never
 * touches the repository; this rule is what stops a future change from
 * quietly routing a BLP handler back through the repository-backed method
 * instead.
 *
 * <p>Named callers, not "everyone except an allowed list": task 7's own
 * migration left {@code findByIdAndDeskId} with no production caller at all
 * ({@code OrderQueryController} turned out to call
 * {@code TradingOrderRepository.findByIdAndDeskId} directly, a same-named
 * method on a different class - not this one). An "allowed caller" list would
 * have nothing real to name and would not have caught that mistake; naming
 * the two classes this rule actually cares about does.
 *
 * <p>Deliberately narrower than "no BLP repository access at all": task 7
 * only migrated this one method's callers.
 * {@code OrderStateCache.existsById}/{@code findProcessedById} still fall
 * back to the database and are still called from {@code OrderCommandHandler}
 * today - moving them is task 8's job (deciding what "the index cannot
 * answer" means deterministically), not this guard's to anticipate. A rule
 * that already failed before task 8 existed would not have stayed a useful
 * signal.
 */
class MemoryOnlyBoundaryTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.emporia.ordermanagement", "com.emporia.execution");
    }

    @Test
    void blpHandlersDoNotCallTheRepositoryBackedOrderLookup() {
        ArchRule rule = noClasses()
                .that().belongToAnyOf(OrderCommandHandler.class, ExecutionCommandHandler.class)
                .should().callMethod(OrderStateCache.class, "findByIdAndDeskId", UUID.class, String.class)
                .because("LMAX_ARCHITECTURE_REWORK_PLAN.md R2 (BLP is memory-only): the BLP handlers "
                        + "use findLiveByIdAndDeskMemoryOnly, which never reaches the repository. "
                        + "findByIdAndDeskId's database fallback is for a general cache consumer "
                        + "outside the single writer thread.");
        rule.check(classes);
    }
}
