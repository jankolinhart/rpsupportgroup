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
import java.util.regex.Pattern;

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
    private final CorpusRejectionRecorder rejections;
    private final SupportGroupConfigRepository configs;
    private final int retention;

    public MarkerCorpusService(MarkerCorpusSnapshotRepository snapshots, CorpusSnapshotItemRepository items,
                               CorpusRepresentativeRepository representatives, SupportGroupConfigRepository configs,
                               CorpusRejectionRecorder rejections,
                               @Value("${rp.corpus.retention:8}") int retention) {
        this.snapshots = snapshots;
        this.items = items;
        this.representatives = representatives;
        this.configs = configs;
        this.rejections = rejections;
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
        List<CorpusItemPayload> malformed = payloads.stream()
                .filter(p -> p.dHash() == null || !WELL_FORMED_DHASH.matcher(p.dHash()).matches())
                .toList();
        List<CorpusSnapshotItem> batch = payloads.stream().map(p -> {
            CorpusSnapshotItem item = CorpusSnapshotItem.of(snapshotId, p.shortcode(), p.authorUsername(), p.dHash(),
                    p.postedAt(), p.ordinal());
            if (malformed.contains(p)) {
                item.markUnusable("fingerprint is not 64 binary digits: " + abbreviate(p.dHash()));
            }
            return item;
        }).toList();
        if (!malformed.isEmpty()) {
            // ⚠️ VOID THE WHOLE PASS, do not salvage the good rows. This is the deepest producer boundary in the
            // system: what lands here becomes the vetted references every OTHER client matches against, at a 4–10
            // bit tolerance. One malformed fingerprint admitted here is a reference nothing can ever match, and it
            // is invisible — it looks like a marker whose owner changed their picture. The items are still written,
            // flagged unusable, so the failure can be diagnosed rather than merely re-run into.
            CorpusItemPayload first = malformed.getFirst();
            String reason = malformed.size() + " of " + payloads.size() + " item(s) carried a malformed dHash "
                    + "(first: " + first.shortcode() + " = " + abbreviate(first.dHash()) + ")";
            // Committed independently — the 400 below rolls THIS transaction back, and the record of the fault
            // must not go with it.
            rejections.voidSnapshot(snapshotId, batch, reason);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "snapshot " + snapshotId + " rejected — " + reason + ". Re-run the whole pass; a partial "
                    + "corpus is preferable to a reference no client can match.");
        }
        items.saveAll(batch);
        snapshot.addItems(payloads.size());
        return snapshots.save(snapshot);
    }

    /** A fingerprint is 64 binary digits — anything else is not a dHash, whatever its type says. */
    private static final Pattern WELL_FORMED_DHASH = Pattern.compile("^[01]{64}$");

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 24 ? "'" + value + "'" : "'" + value.substring(0, 24) + "…' (" + value.length() + " chars)";
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

    /**
     * The representative thumbnail for a post, searched across the group's snapshots NEWEST-FIRST — the picture a
     * vetted reference's {@code shortcode} actually refers to, wherever it lives.
     *
     * <p>⚠️ Exists because "the newest snapshot" is not "the snapshot the reference came from" (19/08/2026,
     * observed live on the first clean-slate re-vet). The operator vetted from a SEALED 5229-item pass while an
     * EMPTY, OPEN snapshot — a deep scrape cancelled seconds after starting — sat newer in the list. The enricher
     * resolved every representative against that empty pass, found nothing, and stamped no display image on any
     * reference: every picture surface in the product went dark, from the client's marker strip to the re-vet
     * cards' "vetted picture" panels. Falling through per shortcode makes an empty or unrelated newer pass
     * harmless while still preferring the freshest copy of the picture when several passes carry the post.</p>
     *
     * <p>OPEN passes are skipped (mid-write; the scrape may still be streaming) and REJECTED ones are poisoned by
     * definition ({@link SnapshotStatus}). SEALED and INTERRUPTED both serve: an interrupted pass's
     * already-shipped items are usable (take-what-we-get), and that includes its representatives.</p>
     */
    @Transactional(readOnly = true)
    public Optional<CorpusRepresentative> findRepresentative(String igAccount, String shortcode) {
        if (igAccount == null || shortcode == null || shortcode.isBlank()) {
            return Optional.empty();
        }
        return snapshots.findByIgAccountOrderByCreatedAtDesc(igAccount).stream()
                .filter(snap -> snap.getStatus() == SnapshotStatus.SEALED
                        || snap.getStatus() == SnapshotStatus.INTERRUPTED)
                .map(snap -> representatives.findBySnapshotIdAndShortcode(snap.getId(), shortcode))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * The fingerprint a CLIENT computed for one of a group's posts, newest pass first — the only fingerprint that
     * may ever be written into a vetted profile for it.
     *
     * <p>This is how a corrupt reference is repaired without hashing anything here. The value that landed in
     * `glowbloggeragency`'s Sunday START was the reference's own post shortcode, so the post is known — and a deep
     * scrape has already streamed a client-computed hash for that very post. Copying it is exact; recomputing it
     * with this service's Java hasher lands 15–34 bits away and would never match.</p>
     *
     * <p>Empty when the pass has been pruned, was voided, or the item was burned — in which case the reference
     * genuinely needs a re-vet, and saying so is better than inventing a hash.</p>
     */
    @Transactional(readOnly = true)
    public Optional<String> clientHashForPost(String igAccount, String shortcode) {
        if (igAccount == null || shortcode == null || shortcode.isBlank()) {
            return Optional.empty();
        }
        return items.findUsableByGroupAndShortcode(igAccount, shortcode).stream()
                .map(CorpusSnapshotItem::getDHash)
                .filter(h -> h != null && WELL_FORMED_DHASH.matcher(h).matches())
                .findFirst();
    }

    /**
     * Burn every corpus row of a group that carries one exact fingerprint value, so it can never again be picked
     * as a marker reference. Returns how many were burned.
     */
    @Transactional
    public int burn(String igAccount, String dHash, String reason) {
        if (igAccount == null || dHash == null || dHash.isBlank()) {
            return 0;
        }
        List<CorpusSnapshotItem> doomed = items.findByGroupAndDHash(igAccount, dHash).stream()
                .filter(i -> !i.isUnusable())
                .toList();
        doomed.forEach(i -> i.markUnusable(reason));
        items.saveAll(doomed);
        return doomed.size();
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
