package com.emporia.journal;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * High-Throughput Off-Heap Binary Write-Ahead Log (WAL) Store SPI contract.
 */
public interface WriteAheadLogStore extends AutoCloseable {

    /**
     * Append a raw binary payload byte array to the WAL journal.
     */
    long append(byte[] payload) throws IOException;

    /**
     * Append a Direct ByteBuffer payload to the WAL journal (zero-copy hot path).
     */
    long append(ByteBuffer buffer) throws IOException;

    /**
     * Force sync (fsync) dirty pages to physical storage.
     */
    void flush() throws IOException;

    /**
     * Total bytes written to the journal.
     */
    long getBytesWritten();
}
