package com.emporia.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void prunesOldExchangeCoreCheckpointFilesAfterManifestSave() throws IOException {
        ExchangeCoreCheckpointStore store = new ExchangeCoreCheckpointStore(directory);
        checkpointFiles(1);
        checkpointFiles(2);
        checkpointFiles(3);
        Files.writeString(directory.resolve("emporia-simulation_journal_1.ecj"), "journal");
        Files.writeString(directory.resolve("unrelated.txt"), "keep");

        store.save(3, Set.of(7));
        store.pruneRetainingLatest(2);

        assertThat(fileNames()).containsExactlyInAnyOrder(
                "emporia-exchange-core.latest",
                "emporia-simulation_snapshot_2_ME0.ecs",
                "emporia-simulation_snapshot_2_RE0.ecs",
                "emporia-simulation_dma_lifecycle_2.dmas",
                "emporia-simulation_snapshot_3_ME0.ecs",
                "emporia-simulation_snapshot_3_RE0.ecs",
                "emporia-simulation_dma_lifecycle_3.dmas",
                "emporia-simulation_journal_1.ecj",
                "unrelated.txt");
    }

    @Test
    void keepsManifestCheckpointEvenWhenOnlyOneCheckpointIsRetained() throws IOException {
        ExchangeCoreCheckpointStore store = new ExchangeCoreCheckpointStore(directory);
        checkpointFiles(10);
        checkpointFiles(11);

        store.save(11, Set.of(7));
        store.pruneRetainingLatest(1);

        assertThat(fileNames()).containsExactlyInAnyOrder(
                "emporia-exchange-core.latest",
                "emporia-simulation_snapshot_11_ME0.ecs",
                "emporia-simulation_snapshot_11_RE0.ecs",
                "emporia-simulation_dma_lifecycle_11.dmas");
    }

    @Test
    void rejectsInvalidRetentionWindow() {
        ExchangeCoreCheckpointStore store = new ExchangeCoreCheckpointStore(directory);

        assertThatThrownBy(() -> store.pruneRetainingLatest(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retainedCheckpoints");
    }

    @Test
    void reportsCheckpointStorageStats() throws IOException {
        ExchangeCoreCheckpointStore store = new ExchangeCoreCheckpointStore(directory);
        checkpointFiles(7);
        Files.writeString(directory.resolve("unrelated.txt"), "abc");

        store.save(7, Set.of(3));

        ExchangeCoreCheckpointStore.StorageStats stats = store.stats();
        assertThat(stats.directory()).isEqualTo(directory.toAbsolutePath().normalize());
        assertThat(stats.latestCheckpointId()).contains(7L);
        assertThat(stats.latestCheckpointIdOrZero()).isEqualTo(7L);
        assertThat(stats.checkpointIdCount()).isEqualTo(1);
        assertThat(stats.checkpointFileCount()).isEqualTo(3);
        assertThat(stats.storageBytes()).isGreaterThan(3L);
    }

    @Test
    void failsFastWhenUsableStorageFallsBelowTheConfiguredFloor() {
        ExchangeCoreCheckpointStore store = new ExchangeCoreCheckpointStore(directory);

        assertThatThrownBy(() -> store.requireUsableSpace(Long.MAX_VALUE))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("below required");
    }

    @Test
    void latestCheckpointRecordBehaviors() {
        ExchangeCoreCheckpointStore.LatestCheckpoint cp1 = new ExchangeCoreCheckpointStore.LatestCheckpoint(10L, Set.of(1, 2));
        ExchangeCoreCheckpointStore.LatestCheckpoint cp2 = new ExchangeCoreCheckpointStore.LatestCheckpoint(10L, Set.of(1, 2));

        assertThat(cp1).isEqualTo(cp2);
        assertThat(cp1.hashCode()).isEqualTo(cp2.hashCode());
        assertThat(cp1.toString()).contains("checkpointId=10");
    }

    private void checkpointFiles(long checkpointId) throws IOException {
        Files.writeString(directory.resolve("emporia-simulation_snapshot_" + checkpointId + "_ME0.ecs"), "matching");
        Files.writeString(directory.resolve("emporia-simulation_snapshot_" + checkpointId + "_RE0.ecs"), "risk");
        Files.writeString(directory.resolve("emporia-simulation_dma_lifecycle_" + checkpointId + ".dmas"), "lifecycle");
    }

    private List<String> fileNames() throws IOException {
        try (var files = Files.list(directory)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }
}
