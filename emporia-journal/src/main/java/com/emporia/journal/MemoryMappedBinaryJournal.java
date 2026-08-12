package com.emporia.journal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Off-Heap Direct Memory-Mapped Binary File Write-Ahead Log Engine.
 * Provides sub-microsecond binary event sourcing journal appending with kernel-managed page caching.
 */
public class MemoryMappedBinaryJournal implements WriteAheadLogStore {
    private static final Logger log = LoggerFactory.getLogger(MemoryMappedBinaryJournal.class);

    private final Path journalPath;
    private final FileChannel channel;
    private final RandomAccessFile file;
    private final MappedByteBuffer mappedBuffer;
    private final AtomicLong position = new AtomicLong(0L);
    private final long capacity;
    private volatile boolean closed = false;

    public MemoryMappedBinaryJournal(Path journalPath, long capacityBytes) throws IOException {
        this.journalPath = journalPath;
        this.capacity = capacityBytes;

        log.info("[WAL Journal] Initializing Memory-Mapped File Journal at {} (capacity: {} MB)...",
                journalPath, capacityBytes / (1024 * 1024));

        this.file = new RandomAccessFile(journalPath.toFile(), "rw");
        this.channel = file.getChannel();
        this.mappedBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, capacityBytes);
    }

    @Override
    public synchronized long append(byte[] payload) throws IOException {
        checkOpen();
        if (payload == null || payload.length == 0) return position.get();

        int len = payload.length;
        if (mappedBuffer.position() + len + 4 > capacity) {
            throw new IOException("WAL Journal capacity exceeded (" + capacity + " bytes)");
        }

        long writtenPos = position.get();
        mappedBuffer.putInt(len);
        mappedBuffer.put(payload);
        position.addAndGet(len + 4);

        return writtenPos;
    }

    @Override
    public synchronized long append(ByteBuffer buffer) throws IOException {
        checkOpen();
        if (buffer == null || !buffer.hasRemaining()) return position.get();

        int len = buffer.remaining();
        if (mappedBuffer.position() + len + 4 > capacity) {
            throw new IOException("WAL Journal capacity exceeded (" + capacity + " bytes)");
        }

        long writtenPos = position.get();
        mappedBuffer.putInt(len);
        mappedBuffer.put(buffer);
        position.addAndGet(len + 4);

        return writtenPos;
    }

    @Override
    public synchronized void flush() {
        if (!closed && mappedBuffer != null) {
            mappedBuffer.force();
        }
    }

    @Override
    public long getBytesWritten() {
        return position.get();
    }

    public Path getJournalPath() {
        return journalPath;
    }

    public boolean isClosed() {
        return closed;
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("WAL Journal is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            flush();
            if (channel != null) channel.close();
            if (file != null) file.close();
            log.info("[WAL Journal] Memory-mapped journal at {} safely closed.", journalPath);
        }
    }
}
