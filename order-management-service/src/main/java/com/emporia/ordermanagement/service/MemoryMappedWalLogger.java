package com.emporia.ordermanagement.service;

import com.emporia.events.sbe.SbeEncoderDecoder;
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

    private static final int DEFAULT_FILE_SIZE = 64 * 1024 * 1024; // 64 MB

    private final Path walPath;
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
            log.info("Memory-Mapped WAL initialized: path={} sizeMB={}", walPath, capacityBytes / (1024 * 1024));
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

        int requiredBytes = 4 + sbePayload.length; // 4-byte length prefix + payload
        if (mappedBuffer.remaining() < requiredBytes) {
            log.warn("Memory-Mapped WAL log file full (capacity {} MB)", capacityBytes / (1024 * 1024));
            return false;
        }

        mappedBuffer.putInt(sbePayload.length);
        mappedBuffer.put(sbePayload);
        return true;
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
