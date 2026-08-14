package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.ordermanagement.model.ProcessedCommand;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandDedupIndexTest {

    /**
     * The invariant the whole design rests on. A false negative lets a duplicate
     * order through, which is a duplicate position - so this is a property over
     * arbitrary input rather than a handful of examples.
     */
    @Property
    void neverReportsARememberedIdentifierAsNew(@ForAll @Size(min = 1, max = 200) List<Long> seeds) {
        // jqwik has no built-in arbitrary for UUID, so the ids are derived from
        // generated longs; both halves vary, which is what the hashing uses.
        List<UUID> ids = seeds.stream().map(seed -> new UUID(seed, ~seed)).toList();
        CommandDedupIndex index = new CommandDedupIndex(1_000, 0.001, 100);
        ids.forEach(index::remember);

        assertThat(ids).allSatisfy(id -> assertThat(index.definitelyNew(id)).isFalse());
    }

    @Test
    void reportsUnseenIdentifiersAsNew() {
        CommandDedupIndex index = new CommandDedupIndex(10_000, 0.001, 100);
        index.remember(UUID.randomUUID());

        // Not a guarantee for any single id - the filter is allowed to be wrong
        // in this direction - so assert on the rate rather than on one answer.
        int seenAsNew = 0;
        for (int i = 0; i < 1_000; i++) {
            if (index.definitelyNew(UUID.randomUUID())) seenAsNew++;
        }
        assertThat(seenAsNew).isGreaterThan(990);
    }

    @Test
    void staysWithinTheRequestedFalsePositiveRate() {
        int entries = 10_000;
        CommandDedupIndex index = new CommandDedupIndex(entries, 0.01, 100);
        for (int i = 0; i < entries; i++) {
            index.remember(UUID.randomUUID());
        }

        int falsePositives = 0;
        int probes = 20_000;
        for (int i = 0; i < probes; i++) {
            if (!index.definitelyNew(UUID.randomUUID())) falsePositives++;
        }
        // Allow headroom over the nominal 1%: this is a statistical bound, and a
        // tight assertion here would fail intermittently for no useful reason.
        assertThat((double) falsePositives / probes).isLessThan(0.03);
    }

    @Test
    void recallsThePreviousResultForARetry() {
        CommandDedupIndex index = new CommandDedupIndex(1_000, 0.001, 100);
        UUID commandId = UUID.randomUUID();
        ProcessedCommand processed = new ProcessedCommand(
                new OrderCommandResult(SCHEMA_VERSION, commandId, true, 201, null, "{}"));

        index.remember(processed);

        assertThat(index.definitelyNew(commandId)).isFalse();
        assertThat(index.recall(commandId)).contains(processed);
    }

    @Test
    void recallIsEmptyForAnIdentifierOnlyInTheFilter() {
        CommandDedupIndex index = new CommandDedupIndex(1_000, 0.001, 100);
        UUID orderId = UUID.randomUUID();
        index.remember(orderId);

        // orderId is guarded by the filter but has no result to return; the two
        // tiers answer different questions and only one of them applies here.
        assertThat(index.definitelyNew(orderId)).isFalse();
        assertThat(index.recall(orderId)).isEmpty();
    }

    @Test
    void sizesItselfFromTheRequestedErrorRate() {
        CommandDedupIndex tolerant = new CommandDedupIndex(1_000_000, 0.01, 100);
        CommandDedupIndex strict = new CommandDedupIndex(1_000_000, 0.0001, 100);

        assertThat(strict.bitCount()).isGreaterThan(tolerant.bitCount());
        assertThat(strict.hashCount()).isGreaterThan(tolerant.hashCount());
        // ~1.8 bytes per entry at 0.1%, so a session of 3.5M fits in single-digit MB.
        CommandDedupIndex session = new CommandDedupIndex(3_500_000, 0.001, 100);
        assertThat(session.bitCount() / 8 / 1024 / 1024).isLessThan(8);
    }

    @Test
    void rejectsNonsensicalSizing() {
        assertThatThrownBy(() -> new CommandDedupIndex(0, 0.001, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandDedupIndex(1_000, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandDedupIndex(1_000, 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
