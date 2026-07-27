package com.emporia.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

final class ExchangeCoreCheckpointStore {
    private static final String FILE_NAME = "emporia-exchange-core.latest";

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
                + "symbols=" + symbols.stream()
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

    record LatestCheckpoint(long checkpointId, Set<Integer> symbols) {
        LatestCheckpoint {
            if (checkpointId <= 0) {
                throw new IllegalArgumentException("checkpointId must be positive");
            }
            symbols = Set.copyOf(symbols);
        }
    }
}
