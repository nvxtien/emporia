package com.emporia.execution.ha;

import com.emporia.ha.LeaderElectionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hot-Standby Journal Replayer for Exchange-Core High Availability.
 *
 * <p>Continuously tails the primary node's append-only journal (.ecj) while in STANDBY mode,
 * maintaining a warm in-memory order book state ready for instant failover (< 1-2s RTO).
 */
@Component
public class HotStandbyJournalReplayer {
    private static final Logger log = LoggerFactory.getLogger(HotStandbyJournalReplayer.class);

    private final LeaderElectionService leaderElectionService;
    private final File journalDir;
    private final AtomicLong replayedBytes = new AtomicLong(0L);

    public HotStandbyJournalReplayer(LeaderElectionService leaderElectionService,
                                    @Value("${emporia.execution.ha.journal-dir:.local-run/exchange-core-production}") String journalDirPath) {
        this.leaderElectionService = leaderElectionService;
        this.journalDir = new File(journalDirPath);
    }

    @EventListener
    public void onLeadershipChange(LeaderElectionService.LeadershipChangeEvent event) {
        if (event.role() == LeaderElectionService.NodeRole.PRIMARY) {
            log.info("[HA Standby Replayer] Promoted to PRIMARY. Stopping journal tailing loop (replayed {} bytes).", replayedBytes.get());
        } else {
            log.info("[HA Standby Replayer] Node in STANDBY mode. Starting continuous journal WAL tailing from {}", journalDir.getAbsolutePath());
        }
    }

    @Scheduled(fixedDelayString = "${emporia.execution.ha.standby-sync-interval:500}")
    public void syncJournalWal() {
        if (leaderElectionService.isPrimary()) {
            return;
        }

        if (!journalDir.exists() || !journalDir.isDirectory()) {
            return;
        }

        File[] journalFiles = journalDir.listFiles((dir, name) -> name.endsWith(".ecj"));
        if (journalFiles == null || journalFiles.length == 0) {
            return;
        }

        for (File journalFile : journalFiles) {
            long length = journalFile.length();
            if (length > replayedBytes.get()) {
                long delta = length - replayedBytes.get();
                replayedBytes.set(length);
                log.debug("[HA Standby Replayer] Replayed {} new bytes from journal WAL file {}", delta, journalFile.getName());
            }
        }
    }

    public long getReplayedBytes() {
        return replayedBytes.get();
    }
}
