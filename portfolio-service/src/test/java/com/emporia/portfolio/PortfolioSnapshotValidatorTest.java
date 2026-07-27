package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioSnapshotValidatorTest {

    private final PortfolioSnapshotValidator validator =
            new PortfolioSnapshotValidator();

    @Test
    void validatesAndSortsACompleteSnapshot() {
        final ValidatedPortfolioSnapshot snapshot =
                validator.validate(
                        13,
                        101,
                        "exchange-1:13:101",
                        snapshot(
                                List.of(
                                        new Balance(20_001, 5L),
                                        new Balance(840, 0L))));

        assertThat(snapshot.exchangeId()).isEqualTo("exchange-1");
        assertThat(snapshot.deliveryId()).isEqualTo(13);
        assertThat(snapshot.clientId()).isEqualTo(101);
        assertThat(snapshot.availableBalances())
                .containsExactlyEntriesOf(
                        Map.of(840, 0L, 20_001, 5L));
    }

    @Test
    void rejectsPathKeyAndBodyMismatches() {
        assertThatThrownBy(() -> validator.validate(
                14,
                101,
                "exchange-1:14:101",
                snapshot(List.of())))
                .isInstanceOf(PortfolioContractException.class)
                .hasMessageContaining("deliveryId");

        assertThatThrownBy(() -> validator.validate(
                13,
                101,
                "other-key",
                snapshot(List.of())))
                .isInstanceOf(PortfolioContractException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void rejectsDuplicateOrNegativeBalances() {
        assertThatThrownBy(() -> validator.validate(
                13,
                101,
                "exchange-1:13:101",
                snapshot(List.of(
                        new Balance(840, 10L),
                        new Balance(840, 20L)))))
                .isInstanceOf(PortfolioContractException.class)
                .hasMessageContaining("duplicate");

        assertThatThrownBy(() -> validator.validate(
                13,
                101,
                "exchange-1:13:101",
                snapshot(List.of(new Balance(840, -1L)))))
                .isInstanceOf(PortfolioContractException.class)
                .hasMessageContaining("non-negative");
    }

    private static Snapshot snapshot(
            final List<Balance> balances) {
        return new Snapshot(
                PortfolioContracts.SCHEMA_VERSION,
                "exchange-1",
                13L,
                101L,
                balances);
    }
}
