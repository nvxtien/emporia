package com.emporia.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeCoreCheckpointStoreTest {

    @TempDir
    Path directory;

    @Test
    void savesAndLoadsTheLatestCheckpointManifest() throws IOException {
        ExchangeCoreCheckpointStore store = new ExchangeCoreCheckpointStore(directory);

        store.save(42, Set.of(7, 3));

        assertThat(store.load()).hasValueSatisfying(latest -> {
            assertThat(latest.checkpointId()).isEqualTo(42);
            assertThat(latest.symbols()).containsExactlyInAnyOrder(3, 7);
        });
    }

    @Test
    void reportsMissingManifestAsEmpty() throws IOException {
        ExchangeCoreCheckpointStore store = new ExchangeCoreCheckpointStore(directory);

        assertThat(store.load()).isEmpty();
    }

    @Test
    void rejectsInvalidCheckpointIds() {
        ExchangeCoreCheckpointStore store = new ExchangeCoreCheckpointStore(directory);

        assertThatThrownBy(() -> store.save(0, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkpointId");
    }

    @Test
    void latestCheckpointRecordBehaviors() {
        ExchangeCoreCheckpointStore.LatestCheckpoint cp1 = new ExchangeCoreCheckpointStore.LatestCheckpoint(10L, Set.of(1, 2));
        ExchangeCoreCheckpointStore.LatestCheckpoint cp2 = new ExchangeCoreCheckpointStore.LatestCheckpoint(10L, Set.of(1, 2));

        assertThat(cp1).isEqualTo(cp2);
        assertThat(cp1.hashCode()).isEqualTo(cp2.hashCode());
        assertThat(cp1.toString()).contains("checkpointId=10");
    }
}
