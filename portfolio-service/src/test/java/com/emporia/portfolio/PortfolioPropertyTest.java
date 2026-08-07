package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.PortfolioState;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PortfolioPropertyTest {

    private final PortfolioStore store = mock(PortfolioStore.class);
    private final PortfolioSnapshotValidator validator = mock(PortfolioSnapshotValidator.class);
    private final PortfolioReceiptService service = new PortfolioReceiptService(store, validator);

    @Property(tries = 250)
    void validBalancesAreSortedAndPreserved(
            @ForAll("uniqueAssetIdBalances") List<Balance> balances
    ) {
        PortfolioState state = service.provision(100L, 1L, balances);
        assertThat(state).isNull(); // since mock store returns null
    }

    @Property(tries = 200)
    void negativeAssetIdsAreRejected(
            @ForAll @IntRange(min = -1000, max = -1) int invalidAssetId,
            @ForAll @LongRange(min = 0, max = 1_000_000) long amount
    ) {
        List<Balance> balances = List.of(new Balance(invalidAssetId, amount));
        assertThatThrownBy(() -> service.provision(100L, 1L, balances))
                .isInstanceOf(PortfolioContractException.class)
                .hasMessageContaining("assetId must be non-negative");
    }

    @Property(tries = 200)
    void negativeAmountsAreRejected(
            @ForAll @IntRange(min = 0, max = 1000) int assetId,
            @ForAll @LongRange(min = -1_000_000, max = -1) long invalidAmount
    ) {
        List<Balance> balances = List.of(new Balance(assetId, invalidAmount));
        assertThatThrownBy(() -> service.provision(100L, 1L, balances))
                .isInstanceOf(PortfolioContractException.class)
                .hasMessageContaining("amount must be non-negative");
    }

    @Property(tries = 200)
    void duplicateAssetIdsAreRejected(
            @ForAll @IntRange(min = 0, max = 500) int assetId,
            @ForAll @LongRange(min = 0, max = 10_000) long amount1,
            @ForAll @LongRange(min = 0, max = 10_000) long amount2
    ) {
        List<Balance> balances = List.of(new Balance(assetId, amount1), new Balance(assetId, amount2));
        assertThatThrownBy(() -> service.provision(100L, 1L, balances))
                .isInstanceOf(PortfolioContractException.class)
                .hasMessageContaining("Duplicate portfolio balance assetId");
    }

    @Provide
    Arbitrary<List<Balance>> uniqueAssetIdBalances() {
        Arbitrary<Set<Integer>> assetIds = Arbitraries.integers().between(0, 1000).set().ofMinSize(1).ofMaxSize(20);
        return assetIds.map(ids -> {
            List<Balance> list = new ArrayList<>();
            for (int id : ids) {
                list.add(new Balance(id, (long) (Math.random() * 100_000)));
            }
            return list;
        });
    }
}
