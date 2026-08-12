package com.emporia.ha;

import com.emporia.ha.provider.LocalFileLeaderProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaderElectionServiceTest {

    @Test
    void singleNodeModeAssumesPrimaryRole(@TempDir Path tempDir) {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        Path lockPath = tempDir.resolve("test-ha.lock");
        LeaderElectionProvider provider = new LocalFileLeaderProvider(lockPath.toString());
        LeaderElectionService service = new LeaderElectionService(provider, publisher, false);

        service.checkLeadership();

        assertTrue(service.isPrimary());
        assertEquals(LeaderElectionService.NodeRole.PRIMARY, service.getRole());
        verify(publisher, times(1)).publishEvent(any(LeaderElectionService.LeadershipChangeEvent.class));
    }

    @Test
    void activeHaModeAcquiresLeadershipLock(@TempDir Path tempDir) {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        Path lockPath = tempDir.resolve("test-ha.lock");
        LeaderElectionProvider provider = new LocalFileLeaderProvider(lockPath.toString());
        LeaderElectionService service = new LeaderElectionService(provider, publisher, true);

        service.checkLeadership();

        assertTrue(service.isPrimary());
        assertEquals(LeaderElectionService.NodeRole.PRIMARY, service.getRole());
        assertTrue(service.getLeaderEpoch() > 1);
        assertEquals("local-filelock", service.getProviderName());
    }
}
