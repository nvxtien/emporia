package com.emporia.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryMappedBinaryJournalTest {

    @Test
    void appendsAndFlushesBinaryPayloads(@TempDir Path tempDir) throws IOException {
        Path journalFile = tempDir.resolve("test-journal.wal");
        try (MemoryMappedBinaryJournal journal = new MemoryMappedBinaryJournal(journalFile, 1024 * 1024)) {
            byte[] event1 = "ORDER_CREATE:1001".getBytes(StandardCharsets.UTF_8);
            byte[] event2 = "ORDER_FILL:1001".getBytes(StandardCharsets.UTF_8);

            long pos1 = journal.append(event1);
            long pos2 = journal.append(event2);

            journal.flush();

            assertThat(pos1).isEqualTo(0L);
            assertThat(pos2).isEqualTo(event1.length + 4);
            assertThat(journal.getBytesWritten()).isEqualTo(event1.length + event2.length + 8);
        }
    }

    @Test
    void appendsByteBufferDirectPayloads(@TempDir Path tempDir) throws IOException {
        Path journalFile = tempDir.resolve("test-direct-journal.wal");
        try (MemoryMappedBinaryJournal journal = new MemoryMappedBinaryJournal(journalFile, 1024)) {
            byte[] payload = "ZERO_COPY_EVENT".getBytes(StandardCharsets.UTF_8);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(payload.length);
            buffer.put(payload);
            buffer.flip();

            long pos = journal.append(buffer);
            journal.flush();

            assertThat(pos).isEqualTo(0L);
            assertThat(journal.getBytesWritten()).isEqualTo(payload.length + 4);
        }
    }

    @Test
    void handlesEmptyOrNullBufferAppends(@TempDir Path tempDir) throws IOException {
        Path journalFile = tempDir.resolve("test-empty-journal.wal");
        try (MemoryMappedBinaryJournal journal = new MemoryMappedBinaryJournal(journalFile, 1024)) {
            assertThat(journal.append((byte[]) null)).isEqualTo(0L);
            assertThat(journal.append(new byte[0])).isEqualTo(0L);
            assertThat(journal.append((java.nio.ByteBuffer) null)).isEqualTo(0L);
            assertThat(journal.append(java.nio.ByteBuffer.allocate(0))).isEqualTo(0L);
        }
    }
}
