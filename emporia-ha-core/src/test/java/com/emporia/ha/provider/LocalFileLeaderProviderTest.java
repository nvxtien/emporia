package com.emporia.ha.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileLeaderProviderTest {

    @Test
    void acquiresAndReleasesFileLock(@TempDir Path tempDir) {
        Path lockPath = tempDir.resolve("sub/dir/test.lock");
        LocalFileLeaderProvider provider = new LocalFileLeaderProvider(lockPath.toString());

        assertThat(provider.getProviderName()).isEqualTo("local-filelock");
        assertThat(provider.tryAcquireOrRenewLease()).isTrue();

        // Second attempt while lock is valid returns true
        assertThat(provider.tryAcquireOrRenewLease()).isTrue();

        provider.releaseLease();

        // After release, can acquire again
        assertThat(provider.tryAcquireOrRenewLease()).isTrue();
        provider.releaseLease();
    }
}
