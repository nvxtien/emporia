package com.emporia.execution.ha;

import com.emporia.ha.LeaderElectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HotStandbyJournalReplayerTest {

    @Test
    void standbyNodeTailsAndReplaysNewJournalBytes(@TempDir Path tempDir) throws IOException {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isPrimary()).thenReturn(false);

        Path journalFile = tempDir.resolve("00000001.ecj");
        Files.writeString(journalFile, "SAMPLE_WAL_JOURNAL_DATA_PAYLOAD");

        HotStandbyJournalReplayer replayer = new HotStandbyJournalReplayer(leaderElectionService, tempDir.toString());
        replayer.syncJournalWal();

        assertTrue(replayer.getReplayedBytes() > 0);
    }

    @Test
    void primaryNodeSkipsJournalReplay(@TempDir Path tempDir) throws IOException {
        LeaderElectionService leaderElectionService = mock(LeaderElectionService.class);
        when(leaderElectionService.isPrimary()).thenReturn(true);

        Path journalFile = tempDir.resolve("00000001.ecj");
        Files.writeString(journalFile, "SAMPLE_WAL_JOURNAL_DATA_PAYLOAD");

        HotStandbyJournalReplayer replayer = new HotStandbyJournalReplayer(leaderElectionService, tempDir.toString());
        replayer.syncJournalWal();

        assertEquals(0L, replayer.getReplayedBytes());
    }
}
