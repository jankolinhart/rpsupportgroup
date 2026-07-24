package com.reelypops.rpsupportgroup.corpus;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Periodic corpus GC (P1, slice 3c): sweeps orphaned OPEN snapshots — a client that opened a pass then crashed / went
 * offline — to INTERRUPTED once they have been idle past {@code rp.corpus.stale-ttl}, so the store never fills with
 * never-completed streams. Retention (the newest N per config) is pruned inline on seal by {@link MarkerCorpusService}.
 */
@Component
public class CorpusMaintenanceScheduler {

    private final MarkerCorpusService service;
    private final Duration staleTtl;

    public CorpusMaintenanceScheduler(MarkerCorpusService service,
                                      @Value("${rp.corpus.stale-ttl:PT6H}") Duration staleTtl) {
        this.service = service;
        this.staleTtl = staleTtl;
    }

    /** Mark OPEN snapshots older than the stale TTL as INTERRUPTED. Runs on the configured fixed delay. */
    @Scheduled(fixedDelayString = "${rp.corpus.sweep-interval-ms:3600000}")
    public void sweep() {
        service.sweepStale(Instant.now().minus(staleTtl));
    }
}
