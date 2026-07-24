package com.reelypops.rpsupportgroup.corpus;

import com.reelypops.rpsupportgroup.group.SupportGroupConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The P1 corpus store: opens append-only, snapshot-versioned deep-scrape passes for a group, accepts per-scroll item
 * batches while a snapshot is OPEN, seals a completed pass, and serves the snapshots back as admin vetting evidence.
 */
@Service
public class MarkerCorpusService {

    private static final String DEFAULT_CONTENT_TYPE = "image/jpeg";

    private final MarkerCorpusSnapshotRepository snapshots;
    private final CorpusSnapshotItemRepository items;
    private final CorpusRepresentativeRepository representatives;
    private final SupportGroupConfigRepository configs;
    private final int retention;

    public MarkerCorpusService(MarkerCorpusSnapshotRepository snapshots, CorpusSnapshotItemRepository items,
                               CorpusRepresentativeRepository representatives, SupportGroupConfigRepository configs,
                               @Value("${rp.corpus.retention:8}") int retention) {
        this.snapshots = snapshots;
        this.items = items;
        this.representatives = representatives;
        this.configs = configs;
        this.retention = retention;
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

    /** Seal a completed pass (idempotent, 404 if unknown) and prune the group to the retention window. */
    @Transactional
    public MarkerCorpusSnapshot seal(UUID snapshotId) {
        MarkerCorpusSnapshot snapshot = require(snapshotId);
        snapshot.seal();
        MarkerCorpusSnapshot saved = snapshots.save(snapshot);
        prune(snapshot.getIgAccount());
        return saved;
    }

    /** Upsert a representative thumbnail for a post in a snapshot (404 if the snapshot is unknown, 400 if no bytes). */
    @Transactional
    public void putRepresentative(UUID snapshotId, String shortcode, byte[] image, String contentType) {
        require(snapshotId);
        if (image == null || image.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "representative image is required");
        }
        String type = (contentType == null || contentType.isBlank()) ? DEFAULT_CONTENT_TYPE : contentType;
        CorpusRepresentative rep = representatives.findBySnapshotIdAndShortcode(snapshotId, shortcode)
                .map(existing -> {
                    existing.update(image, type);
                    return existing;
                })
                .orElseGet(() -> CorpusRepresentative.create(snapshotId, shortcode, image, type));
        representatives.save(rep);
    }

    /** The stored representative thumbnail for a post, or empty when none has been contributed. */
    @Transactional(readOnly = true)
    public Optional<CorpusRepresentative> getRepresentative(UUID snapshotId, String shortcode) {
        return representatives.findBySnapshotIdAndShortcode(snapshotId, shortcode);
    }

    /** A group's snapshots, newest first (admin evidence list). */
    @Transactional(readOnly = true)
    public List<MarkerCorpusSnapshot> list(String igAccount) {
        return snapshots.findByIgAccountOrderByCreatedAtDesc(igAccount);
    }

    /** Retention GC: keep only the newest {@code rp.corpus.retention} snapshots for a group; delete the rest. */
    @Transactional
    public int prune(String igAccount) {
        List<MarkerCorpusSnapshot> all = snapshots.findByIgAccountOrderByCreatedAtDesc(igAccount);
        if (all.size() <= retention) {
            return 0;
        }
        List<MarkerCorpusSnapshot> excess = all.subList(retention, all.size());
        snapshots.deleteAll(excess);
        return excess.size();
    }

    /** GC sweep: mark OPEN snapshots opened before {@code cutoff} as INTERRUPTED (orphaned streams). Returns the count. */
    @Transactional
    public int sweepStale(Instant cutoff) {
        List<MarkerCorpusSnapshot> stale = snapshots.findByStatusAndCreatedAtBefore(SnapshotStatus.OPEN, cutoff);
        stale.forEach(MarkerCorpusSnapshot::interrupt);
        snapshots.saveAll(stale);
        return stale.size();
    }

    /** One snapshot with its items in grid order + which shortcodes carry a representative (404 if unknown). */
    @Transactional(readOnly = true)
    public SnapshotDetail detail(UUID snapshotId) {
        MarkerCorpusSnapshot snapshot = require(snapshotId);
        return new SnapshotDetail(snapshot, items.findBySnapshotIdOrderByOrdinalAsc(snapshotId),
                representatives.findShortcodesBySnapshotId(snapshotId));
    }

    private MarkerCorpusSnapshot require(UUID snapshotId) {
        return snapshots.findById(snapshotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no snapshot " + snapshotId));
    }

    /** A snapshot + its items + the shortcodes with a representative (mapped to the detail response by the controller). */
    public record SnapshotDetail(MarkerCorpusSnapshot snapshot, List<CorpusSnapshotItem> items,
                                 List<String> representativeShortcodes) {
    }
}
