package com.reelypops.rpsupportgroup.corpus;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Records a voided deep-scrape pass <strong>in its own transaction</strong>, so the record survives the failure it
 * describes.
 *
 * <p><strong>Why this is a separate bean.</strong> Rejecting a snapshot ends in a 400, and a 400 is thrown as a
 * runtime exception — which rolls the caller's transaction back, taking the rejection with it. The snapshot would
 * be left {@code OPEN}, the offending row unwritten, and the next append would sail through: the guard would appear
 * to work (the batch is refused) while quietly recording nothing. {@code REQUIRES_NEW} commits the evidence before
 * the caller unwinds. It cannot be a private method on the caller either — self-invocation bypasses the proxy and
 * silently reuses the same transaction, which is the same bug wearing a different hat.</p>
 */
@Component
public class CorpusRejectionRecorder {

    private final MarkerCorpusSnapshotRepository snapshots;
    private final CorpusSnapshotItemRepository items;

    public CorpusRejectionRecorder(MarkerCorpusSnapshotRepository snapshots, CorpusSnapshotItemRepository items) {
        this.snapshots = snapshots;
        this.items = items;
    }

    /**
     * Void a pass and file what it streamed. The rows are kept — flagged, never selectable — because "which
     * client sent what, and what did it look like" is the only thing that turns a repeat of this into a diagnosis
     * rather than another re-scrape.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void voidSnapshot(UUID snapshotId, List<CorpusSnapshotItem> batch, String reason) {
        // A row with no fingerprint at all cannot be stored — {@code d_hash} is NOT NULL — so it is described in
        // the snapshot's rejection reason instead of filed. Everything else is kept, flagged, as evidence.
        List<CorpusSnapshotItem> storable = batch.stream().filter(i -> i.getDHash() != null).toList();
        items.saveAll(storable);
        snapshots.findById(snapshotId).ifPresent(s -> {
            s.addItems(storable.size());
            s.reject(reason);
            snapshots.save(s);
        });
    }
}
