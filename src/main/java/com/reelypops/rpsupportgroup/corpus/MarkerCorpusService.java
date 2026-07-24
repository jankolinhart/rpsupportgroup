package com.reelypops.rpsupportgroup.corpus;

import com.reelypops.rpsupportgroup.group.SupportGroupConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * The P1 corpus store: opens append-only, snapshot-versioned deep-scrape passes for a group, accepts per-scroll item
 * batches while a snapshot is OPEN, seals a completed pass, and serves the snapshots back as admin vetting evidence.
 */
@Service
public class MarkerCorpusService {

    private final MarkerCorpusSnapshotRepository snapshots;
    private final CorpusSnapshotItemRepository items;
    private final SupportGroupConfigRepository configs;

    public MarkerCorpusService(MarkerCorpusSnapshotRepository snapshots, CorpusSnapshotItemRepository items,
                               SupportGroupConfigRepository configs) {
        this.snapshots = snapshots;
        this.items = items;
        this.configs = configs;
    }

    /** Open a new snapshot for a known group (404 if no config exists for {@code igAccount}). */
    @Transactional
    public MarkerCorpusSnapshot open(String igAccount, CorpusSource source, String capturedByAccount) {
        if (!configs.existsByIgAccount(igAccount)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no support group config for " + igAccount);
        }
        return snapshots.save(MarkerCorpusSnapshot.open(igAccount, source, capturedByAccount));
    }

    /** Append a per-scroll batch to an OPEN snapshot (409 if already sealed, 404 if unknown). */
    @Transactional
    public MarkerCorpusSnapshot append(UUID snapshotId, List<CorpusItemPayload> payloads) {
        MarkerCorpusSnapshot snapshot = require(snapshotId);
        if (snapshot.getStatus() != SnapshotStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "snapshot " + snapshotId + " is sealed");
        }
        for (CorpusItemPayload p : payloads) {
            items.save(CorpusSnapshotItem.of(snapshotId, p.shortcode(), p.authorUsername(), p.dHash(),
                    p.postedAt(), p.ordinal()));
        }
        snapshot.addItems(payloads.size());
        return snapshots.save(snapshot);
    }

    /** Seal a completed pass (idempotent, 404 if unknown). */
    @Transactional
    public MarkerCorpusSnapshot seal(UUID snapshotId) {
        MarkerCorpusSnapshot snapshot = require(snapshotId);
        snapshot.seal();
        return snapshots.save(snapshot);
    }

    /** A group's snapshots, newest first (admin evidence list). */
    @Transactional(readOnly = true)
    public List<MarkerCorpusSnapshot> list(String igAccount) {
        return snapshots.findByIgAccountOrderByCreatedAtDesc(igAccount);
    }

    /** One snapshot with its items in grid order (404 if unknown). */
    @Transactional(readOnly = true)
    public SnapshotDetail detail(UUID snapshotId) {
        MarkerCorpusSnapshot snapshot = require(snapshotId);
        return new SnapshotDetail(snapshot, items.findBySnapshotIdOrderByOrdinalAsc(snapshotId));
    }

    private MarkerCorpusSnapshot require(UUID snapshotId) {
        return snapshots.findById(snapshotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no snapshot " + snapshotId));
    }

    /** A snapshot + its items (mapped to the detail response by the controller). */
    public record SnapshotDetail(MarkerCorpusSnapshot snapshot, List<CorpusSnapshotItem> items) {
    }
}
