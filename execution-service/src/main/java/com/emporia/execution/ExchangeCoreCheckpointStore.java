package com.emporia.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ExchangeCoreCheckpointStore {
    private static final String FILE_NAME = "emporia-exchange-core.latest";
    private static final Pattern SNAPSHOT_FILE = Pattern.compile(".+_snapshot_(\\d+)_[A-Z]+\\d+\\.ecs");
    private static final Pattern SNAPSHOT_PARTIAL_FILE =
            Pattern.compile(".+_snapshot_(\\d+)_[A-Z]+\\d+\\.ecs\\..+");
    private static final Pattern DMA_LIFECYCLE_FILE = Pattern.compile(".+_dma_lifecycle_(\\d+)\\.dmas");
    private static final Pattern DMA_LIFECYCLE_PARTIAL_FILE =
            Pattern.compile(".+_dma_lifecycle_(\\d+)\\.dmas\\..+");

    private final Path directory;
    private final Path manifest;

    ExchangeCoreCheckpointStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.manifest = this.directory.resolve(FILE_NAME);
    }

    Optional<LatestCheckpoint> load() throws IOException {
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        long checkpointId = 0;
        Set<Integer> symbols = new TreeSet<>();
        try {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (line.startsWith("checkpointId=")) {
                    checkpointId = Long.parseLong(line.substring("checkpointId=".length()).trim());
                } else if (line.startsWith("symbols=")) {
                    String value = line.substring("symbols=".length()).trim();
                    if (!value.isBlank()) {
                        Arrays.stream(value.split(","))
                              .map(String::trim)
                              .filter(part -> !part.isBlank())
                              .map(Integer::parseInt)
                              .forEach(symbols::add);
                    }
                }
            }
        } catch (IllegalArgumentException invalidManifest) {
            throw new IOException("Exchange-core checkpoint manifest is invalid", invalidManifest);
        }
        if (checkpointId <= 0) {
            throw new IOException("Exchange-core checkpoint manifest has no positive checkpointId");
        }
        return Optional.of(new LatestCheckpoint(checkpointId, symbols));
    }

    void save(long checkpointId, Set<Integer> symbols) throws IOException {
        if (checkpointId <= 0) {
            throw new IllegalArgumentException("checkpointId must be positive");
        }
        Files.createDirectories(directory);
        String content = "checkpointId=" + checkpointId + "\n"
                + "symbols="
                + symbols.stream()
                         .sorted()
                         .map(String::valueOf)
                         .reduce((left, right) -> left + "," + right)
                         .orElse("")
                + "\n";
        Path temporary = Files.createTempFile(directory, FILE_NAME, ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void pruneRetainingLatest(int retainedCheckpoints) throws IOException {
        if (retainedCheckpoints < 1) {
            throw new IllegalArgumentException("retainedCheckpoints must be at least 1");
        }
        Optional<LatestCheckpoint> latest = load();
        if (latest.isEmpty() || !Files.isDirectory(directory)) {
            return;
        }

        long latestCheckpointId = latest.get().checkpointId();
        List<CheckpointFile> files = checkpointFiles();
        Map<Long, CheckpointGeneration> generations = checkpointGenerations(files);
        Set<Long> retainedIds = generations.values().stream()
                                           .filter(CheckpointGeneration::complete)
                                           .map(CheckpointGeneration::checkpointId)
                                           .filter(checkpointId -> checkpointId <= latestCheckpointId)
                                           .distinct()
                                           .sorted(Comparator.reverseOrder())
                                           .limit(retainedCheckpoints)
                                           .collect(Collectors.toCollection(HashSet::new));
        retainedIds.add(latestCheckpointId);

        for (CheckpointFile file : files) {
            long checkpointId = file.checkpointId();
            if (checkpointId < latestCheckpointId && !retainedIds.contains(checkpointId)) {
                Files.deleteIfExists(file.path());
            }
        }
    }

    StorageStats stats() throws IOException {
        Optional<LatestCheckpoint> latest = load();
        if (!Files.isDirectory(directory)) {
            return new StorageStats(directory, latest.map(LatestCheckpoint::checkpointId), 0, 0, 0, 0, 0);
        }

        List<CheckpointFile> checkpoints = checkpointFiles();
        Map<Long, CheckpointGeneration> generations = checkpointGenerations(checkpoints);
        long bytes = regularStorageBytes();
        long usableBytes = Files.getFileStore(directory).getUsableSpace();
        long completeCheckpointIds = generations.values().stream()
                                                .filter(CheckpointGeneration::complete)
                                                .count();
        long completeCheckpointFiles = checkpoints.stream()
                                                 .filter(CheckpointFile::complete)
                                                 .count();
        long partialCheckpointFiles = checkpoints.stream()
                                                 .filter(CheckpointFile::partial)
                                                 .count();
        return new StorageStats(directory, latest.map(LatestCheckpoint::checkpointId),
                                (int) completeCheckpointIds, (int) completeCheckpointFiles,
                                (int) partialCheckpointFiles, bytes, usableBytes);
    }

    long usableStorageBytes() throws IOException {
        Files.createDirectories(directory);
        FileStore store = Files.getFileStore(directory);
        return store.getUsableSpace();
    }

    long maxCheckpointId() throws IOException {
        long maxCheckpointId = load().map(LatestCheckpoint::checkpointId).orElse(0L);
        if (!Files.isDirectory(directory)) {
            return maxCheckpointId;
        }
        for (CheckpointFile file : checkpointFiles()) {
            maxCheckpointId = Math.max(maxCheckpointId, file.checkpointId());
        }
        return maxCheckpointId;
    }

    void requireUsableSpace(long minFreeBytes) throws IOException {
        if (minFreeBytes <= 0) {
            return;
        }
        long usableBytes = usableStorageBytes();
        if (usableBytes < minFreeBytes) {
            throw new IOException("Exchange-core checkpoint storage has only " + usableBytes
                    + " bytes free, below required " + minFreeBytes + " bytes");
        }
    }

    private List<CheckpointFile> checkpointFiles() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .map(ExchangeCoreCheckpointStore::checkpointFile)
                        .flatMap(Optional::stream)
                        .toList();
        }
    }

    private long regularStorageBytes() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            long bytes = 0;
            for (Path path : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                bytes += Files.size(path);
            }
            return bytes;
        }
    }

    private static Optional<CheckpointFile> checkpointFile(Path path) {
        String name = path.getFileName().toString();
        Matcher snapshot = SNAPSHOT_FILE.matcher(name);
        if (snapshot.matches()) {
            return Optional.of(new CheckpointFile(path, Long.parseLong(snapshot.group(1)),
                    CheckpointFileKind.SNAPSHOT));
        }
        Matcher lifecycle = DMA_LIFECYCLE_FILE.matcher(name);
        if (lifecycle.matches()) {
            return Optional.of(new CheckpointFile(path, Long.parseLong(lifecycle.group(1)),
                    CheckpointFileKind.DMA_LIFECYCLE));
        }
        Matcher partialSnapshot = SNAPSHOT_PARTIAL_FILE.matcher(name);
        if (partialSnapshot.matches()) {
            return Optional.of(new CheckpointFile(path, Long.parseLong(partialSnapshot.group(1)),
                    CheckpointFileKind.PARTIAL));
        }
        Matcher partialLifecycle = DMA_LIFECYCLE_PARTIAL_FILE.matcher(name);
        if (partialLifecycle.matches()) {
            return Optional.of(new CheckpointFile(path, Long.parseLong(partialLifecycle.group(1)),
                    CheckpointFileKind.PARTIAL));
        }
        return Optional.empty();
    }

    private static Map<Long, CheckpointGeneration> checkpointGenerations(List<CheckpointFile> files) {
        Map<Long, CheckpointGeneration> generations = new HashMap<>();
        for (CheckpointFile file : files) {
            generations.computeIfAbsent(file.checkpointId(), CheckpointGeneration::new)
                       .add(file);
        }
        return generations;
    }

    record LatestCheckpoint(long checkpointId, Set<Integer> symbols) {
        LatestCheckpoint {
            if (checkpointId <= 0) {
                throw new IllegalArgumentException("checkpointId must be positive");
            }
            symbols = Set.copyOf(symbols);
        }
    }

    private enum CheckpointFileKind {
        SNAPSHOT,
        DMA_LIFECYCLE,
        PARTIAL
    }

    private record CheckpointFile(Path path, long checkpointId, CheckpointFileKind kind) {
        boolean complete() {
            return kind != CheckpointFileKind.PARTIAL;
        }

        boolean partial() {
            return kind == CheckpointFileKind.PARTIAL;
        }
    }

    private static final class CheckpointGeneration {
        private final long checkpointId;
        private boolean hasSnapshot;
        private boolean hasDmaLifecycle;

        private CheckpointGeneration(long checkpointId) {
            this.checkpointId = checkpointId;
        }

        private void add(CheckpointFile file) {
            if (file.kind() == CheckpointFileKind.SNAPSHOT) {
                hasSnapshot = true;
            } else if (file.kind() == CheckpointFileKind.DMA_LIFECYCLE) {
                hasDmaLifecycle = true;
            }
        }

        private long checkpointId() {
            return checkpointId;
        }

        private boolean complete() {
            return hasSnapshot && hasDmaLifecycle;
        }
    }

    record StorageStats(
            Path directory,
            Optional<Long> latestCheckpointId,
            int checkpointIdCount,
            int checkpointFileCount,
            int partialCheckpointFileCount,
            long storageBytes,
            long usableStorageBytes) {
        long latestCheckpointIdOrZero() {
            return latestCheckpointId.orElse(0L);
        }
    }
}
