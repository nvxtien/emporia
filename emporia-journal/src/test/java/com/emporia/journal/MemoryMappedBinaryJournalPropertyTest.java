package com.emporia.journal;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryMappedBinaryJournalPropertyTest {

    @Property
    void bytesWrittenAlwaysMatchesSumOfPayloadLengths(@ForAll @Size(min = 1, max = 10) byte[][] payloads) throws IOException {
        Path tempFile = Files.createTempFile("jqwik-wal", ".tmp");
        tempFile.toFile().deleteOnExit();

        try (MemoryMappedBinaryJournal journal = new MemoryMappedBinaryJournal(tempFile, 1024 * 1024)) {
            long expectedBytes = 0;
            for (byte[] payload : payloads) {
                if (payload != null && payload.length > 0) {
                    journal.append(payload);
                    expectedBytes += payload.length + 4; // 4-byte length prefix + payload
                }
            }
            journal.flush();

            assertThat(journal.getBytesWritten()).isEqualTo(expectedBytes);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
