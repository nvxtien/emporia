package com.emporia.ordermanagement.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ultra-low latency Memory-Mapped File (mmap) Write-Ahead Log (WAL) logger.
 *
 * <p>Appends binary SBE event payloads directly into OS page-cache virtual memory
 * via {@link MappedByteBuffer}. Bypasses JVM file stream I/O and system call context switches,
 * achieving zero-allocation sub-microsecond event persistence.
 */
@Component
public class MemoryMappedWalLogger implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(MemoryMappedWalLogger.class);

    private static final int LENGTH_PREFIX_BYTES = Integer.BYTES;

    private final Path walPath;
    /** Position through which records are handled and queued for the database. */
    private int safePoint;
    private final int capacityBytes;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private RandomAccessFile file;
    private FileChannel channel;
    private MappedByteBuffer mappedBuffer;

    public MemoryMappedWalLogger(
            @Value("${emporia.wal.file-path:#{null}}") String walFilePath,
            @Value("${emporia.wal.file-size-mb:64}") int fileSizeMb) {

        this.capacityBytes = Math.max(1, fileSizeMb) * 1024 * 1024;

        if (walFilePath != null && !walFilePath.isBlank()) {
            this.walPath = Path.of(walFilePath);
            initMapping();
        } else {
            this.walPath = null;
            this.mappedBuffer = null;
        }
    }

    private void initMapping() {
        try {
            if (walPath.getParent() != null) {
                Files.createDirectories(walPath.getParent());
            }
            this.file = new RandomAccessFile(walPath.toFile(), "rw");
            this.channel = file.getChannel();
            this.mappedBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, capacityBytes);
            int liveEnd = liveEndOffset();
            mappedBuffer.position(liveEnd);
            log.info("Memory-Mapped WAL initialized: path={} sizeMB={} liveBytes={}", walPath, capacityBytes / (1024 * 1024), liveEnd);
        } catch (IOException error) {
            log.error("Failed to initialize Memory-Mapped WAL logger at {}", walPath, error);
            throw new IllegalStateException("Memory-Mapped WAL initialization failed", error);
        }
    }

    /**
     * Write an SBE binary payload directly into the memory-mapped log file.
     *
     * @param sbePayload raw SBE binary frame
     * @return {@code true} if written successfully to mapped memory
     */
    public synchronized boolean append(byte[] sbePayload) {
        if (closed.get() || mappedBuffer == null || sbePayload == null || sbePayload.length == 0) {
            return false;
        }

        int requiredBytes = LENGTH_PREFIX_BYTES + sbePayload.length;
        if (mappedBuffer.remaining() < requiredBytes) {
            log.warn("Memory-Mapped WAL log file full (capacity {} MB)", capacityBytes / (1024 * 1024));
            return false;
        }

        mappedBuffer.putInt(sbePayload.length);
        mappedBuffer.put(sbePayload);
        return true;
    }

    /**
     * Marks everything written so far as handled, and therefore queued for the
     * database.
     *
     * <p>Called by the ring's consumer after a command has been applied. Only
     * that thread appends, so the position it observes here is a point every
     * earlier record has passed: those commands are enqueued, and a flush that
     * drains the queues has persisted them.
     */
    public synchronized void markSafePoint() {
        if (closed.get() || mappedBuffer == null) return;
        safePoint = mappedBuffer.position();
    }

    /**
     * Discards the records already persisted, keeping whatever is still in
     * flight.
     *
     * <p>The log covers the gap between a command being accepted and the
     * database write that persists it, so once a flush has drained the queues
     * everything up to the safe point is redundant and its space can be reused.
     * The bytes after it - commands accepted while the flush ran - are moved to
     * the front rather than dropped, because those are exactly the records that
     * still matter.
     *
     * <p>Compacting rather than only rewinding is what bounds the file under
     * continuous load. Rewinding demands a moment when nothing was appended
     * during a flush, which a busy system rarely offers, so the log would climb
     * until it filled and started refusing orders. Compaction reclaims on every
     * flush regardless, leaving only the in-flight window - milliseconds of
     * traffic - however busy the system is.
     *
     * @return bytes reclaimed
     */
    public synchronized int compactToSafePoint() {
        if (closed.get() || mappedBuffer == null || safePoint <= 0) {
            return 0;
        }
        int position = mappedBuffer.position();
        int reclaimed = Math.min(safePoint, position);
        int inFlight = position - reclaimed;
        if (inFlight > 0) {
            byte[] tail = new byte[inFlight];
            mappedBuffer.position(reclaimed);
            mappedBuffer.get(tail);
            mappedBuffer.position(0);
            mappedBuffer.put(tail);
        } else {
            mappedBuffer.position(0);
        }
        // Erase what the move left behind. A reader recovering this file finds
        // the end by the first zero-length frame, and those trailing bytes are
        // intact older records - it would replay commands that were persisted
        // and then reclaimed.
        zeroFrom(inFlight, position);
        mappedBuffer.position(inFlight);
        safePoint = 0;
        return reclaimed;
    }

    /** Blanks {@code [from, to)}, in chunks so a large span costs no large buffer. */
    private void zeroFrom(int from, int to) {
        int remaining = to - from;
        if (remaining <= 0) return;
        byte[] blank = new byte[Math.min(remaining, 8 * 1024)];
        mappedBuffer.position(from);
        while (remaining > 0) {
            int chunk = Math.min(remaining, blank.length);
            mappedBuffer.put(blank, 0, chunk);
            remaining -= chunk;
        }
    }

    /**
     * Reads the records this log still holds, oldest first.
     *
     * <p>Intended for recovery. Reopening a WAL scans to the same live-data
     * boundary this method reads through, so commands appended after replay are
     * written after the recovered records rather than over them.
     *
     * <p>Stops at the first frame whose length is not a plausible record. A file
     * is created at full size and zero-filled, and compaction blanks what it
     * reclaims, so that boundary is the end of the live data.
     */
    public synchronized List<byte[]> readPendingRecords() {
        if (mappedBuffer == null) return List.of();
        List<byte[]> records = new ArrayList<>();
        int cursor = 0;
        int liveEnd = liveEndOffset();
        while (cursor + LENGTH_PREFIX_BYTES <= liveEnd) {
            int length = mappedBuffer.getInt(cursor);
            if (isNotValidFrame(cursor, length)) break;
            byte[] record = new byte[length];
            for (int i = 0; i < length; i++) {
                record[i] = mappedBuffer.get(cursor + LENGTH_PREFIX_BYTES + i);
            }
            records.add(record);
            cursor += LENGTH_PREFIX_BYTES + length;
        }
        return records;
    }

    public boolean isEnabled() {
        return mappedBuffer != null && !closed.get();
    }

    public int position() {
        return mappedBuffer != null ? mappedBuffer.position() : 0;
    }

    public void force() {
        if (mappedBuffer != null) {
            mappedBuffer.force();
        }
    }

    private int liveEndOffset() {
        if (mappedBuffer == null) return 0;
        int cursor = 0;
        while (cursor + LENGTH_PREFIX_BYTES <= capacityBytes) {
            int length = mappedBuffer.getInt(cursor);
            if (isNotValidFrame(cursor, length)) break;
            cursor += LENGTH_PREFIX_BYTES + length;
        }
        return cursor;
    }

    private boolean isNotValidFrame(int cursor, int length) {
        return length <= 0
                || cursor < 0
                || cursor + LENGTH_PREFIX_BYTES + length > capacityBytes;
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            if (mappedBuffer != null) {
                mappedBuffer.force();
            }
            try {
                if (channel != null) channel.close();
                if (file != null) file.close();
                log.info("Memory-Mapped WAL closed cleanly");
            } catch (IOException e) {
                log.warn("Error closing Memory-Mapped WAL file", e);
            }
        }
    }
}
