package com.emporia.ha.provider;

import com.emporia.ha.LeaderElectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/**
 * Local FileLock Leader Election Provider for Local Development and Standalone testing.
 */
public class LocalFileLeaderProvider implements LeaderElectionProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalFileLeaderProvider.class);

    private final File lockFile;
    private RandomAccessFile lockStream;
    private FileLock fileLock;

    public LocalFileLeaderProvider(String lockFilePath) {
        this.lockFile = new File(lockFilePath);
        if (lockFile.getParentFile() != null) {
            lockFile.getParentFile().mkdirs();
        }
    }

    @Override
    public synchronized boolean tryAcquireOrRenewLease() {
        try {
            if (lockStream == null) {
                lockStream = new RandomAccessFile(lockFile, "rw");
            }
            if (fileLock == null || !fileLock.isValid()) {
                fileLock = lockStream.getChannel().tryLock();
            }
            return fileLock != null && fileLock.isValid();
        } catch (Exception e) {
            log.debug("[Local FileLock] Could not acquire file lock: {}", e.getMessage());
            releaseLease();
            return false;
        }
    }

    @Override
    public synchronized void releaseLease() {
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
            }
        } catch (Exception ignored) {}
        try {
            if (lockStream != null) {
                lockStream.close();
            }
        } catch (Exception ignored) {}
        fileLock = null;
        lockStream = null;
    }

    @Override
    public String getProviderName() {
        return "local-filelock";
    }
}
