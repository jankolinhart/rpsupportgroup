package com.reelypops.rpsupportgroup.corpus;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Periodic corpus GC (P1, slice 3c): sweeps orphaned OPEN snapshots — a client that opened a pass then crashed / went
 * offline — to INTERRUPTED once they have gone QUIET past {@code rp.corpus.silent-ttl}, so the store never fills with
 * never-completed streams. Retention (the newest N per config) is pruned inline on seal by {@link MarkerCorpusService}.
 *
 * <p><strong>Quiet, not old.</strong> This used to measure from when a pass was OPENED, which forced a six-hour
 * window — a deep scrape legitimately runs that long, and cutting a live one short would void real work. The
 * cost was that a pass whose machine died two minutes in stayed OPEN for the rest of those six hours, looking
 * exactly like one still streaming, while the console beside it already knew the machine had stopped reporting
 * (operator, 23/09/2026). A live pass appends the whole time it is alive, so silence decides where age could
 * not, and the window drops from hours to half an hour.</p>
 */
@Component
public class CorpusMaintenanceScheduler {

    private final MarkerCorpusService service;
    private final Duration silentTtl;

    /**
     * @param silentTtl how long an OPEN pass may go without a single appended item before it is called
     *                  abandoned. Thirty minutes: a live scrape appends a page every 15-45 seconds, and its
     *                  longest legitimate gap is the client's own idle timeout of ten — so half an hour is
     *                  well clear of a working pass and far short of the six HOURS the old age-based window
     *                  needed, which it needed only because a deep scrape can legitimately run that long.
     */
    public CorpusMaintenanceScheduler(MarkerCorpusService service,
                                      @Value("${rp.corpus.silent-ttl:PT30M}") Duration silentTtl) {
        this.service = service;
        this.silentTtl = silentTtl;
    }

    /**
     * Mark OPEN snapshots that have gone quiet as INTERRUPTED. Runs on the configured fixed delay.
     *
     * <p>The sweep interval bounds how late the answer is, so it is now minutes rather than the hour that
     * suited a six-hour window: there is no point deciding a pass died twenty minutes ago and saying so forty
     * minutes after that.</p>
     */
    @Scheduled(fixedDelayString = "${rp.corpus.sweep-interval-ms:300000}")
    public void sweep() {
        service.sweepStale(Instant.now().minus(silentTtl));
    }
}
