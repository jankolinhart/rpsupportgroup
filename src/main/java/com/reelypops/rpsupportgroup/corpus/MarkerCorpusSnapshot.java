package com.reelypops.rpsupportgroup.corpus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One deep tagged-grid pass captured for a support group (P1 corpus store): the append-only, snapshot-versioned unit
 * of vetting evidence, keyed by the group's Instagram account. Each deep pass is ONE snapshot (never stitched across
 * clients); {@link CorpusSnapshotItem}s stream in per scroll while it is {@link SnapshotStatus#OPEN}. Representative
 * thumbnail bytes + retention GC arrive in later slices. Stored in its own tables so config reads stay lean.
 */
@Entity
@Table(name = "sg_corpus_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarkerCorpusSnapshot {

    @Id
    private UUID id;

    @Column(name = "ig_account", nullable = false, updatable = false)
    private String igAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    private CorpusSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SnapshotStatus status;

    /** The Instagram account whose residential session captured this pass (provenance); null when not reported. */
    @Column(name = "captured_by_account")
    private String capturedByAccount;

    /**
     * The machine fingerprint that ran this pass, as the client reported it. A label on the evidence, never an
     * authorisation — nothing is granted on the strength of it, so a client naming itself is no risk.
     */
    @Column(name = "captured_by_device")
    private String capturedByDevice;

    /**
     * The customer whose machine ran it, taken from the validated token by rpserver and never from the request
     * body: provenance a caller could choose would not be provenance.
     */
    @Column(name = "captured_for_user")
    private UUID capturedForUser;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "rejected_reason", length = 512)
    private String rejectedReason;

    @Column(name = "sealed_at")
    private Instant sealedAt;

    /**
     * When this pass last had items appended, or null until the first one arrives.
     *
     * <p>What the stale sweep measures. Age never could: a deep scrape legitimately runs for hours, so a
     * window wide enough not to cut a live pass short was six of them — and a pass whose machine died two
     * minutes in looked open for the rest of that. A live pass appends the whole time it is alive, so silence
     * says what age cannot.</p>
     */
    @Column(name = "last_item_at")
    private Instant lastItemAt;

    private MarkerCorpusSnapshot(String igAccount, CorpusSource source, String capturedByAccount,
                                 String capturedByDevice, UUID capturedForUser) {
        this.id = UUID.randomUUID();
        this.igAccount = igAccount;
        this.source = source;
        this.capturedByAccount = capturedByAccount;
        this.capturedByDevice = capturedByDevice;
        this.capturedForUser = capturedForUser;
        this.status = SnapshotStatus.OPEN;
        this.itemCount = 0;
    }

    /**
     * Open a new snapshot to stream a deep pass into.
     *
     * <p>All three provenance fields are optional and stay that way. A snapshot from before they were recorded,
     * or from a client too old to send them, is still perfectly good evidence — null says "not recorded", which
     * is honest, where a default would invent a machine that never ran anything.</p>
     */
    public static MarkerCorpusSnapshot open(String igAccount, CorpusSource source, String capturedByAccount,
                                            String capturedByDevice, UUID capturedForUser) {
        return new MarkerCorpusSnapshot(igAccount, source, capturedByAccount, capturedByDevice, capturedForUser);
    }

    /**
     * A pass with no machine or customer recorded — which is what every snapshot opened before 23/09/2026 is,
     * and what one from a client too old to report them still is. Kept as its own door rather than making
     * callers write two nulls, because "not recorded" is a real state here and deserves to read like one.
     */
    public static MarkerCorpusSnapshot open(String igAccount, CorpusSource source, String capturedByAccount) {
        return open(igAccount, source, capturedByAccount, null, null);
    }

    /** Record that {@code count} items were appended this scroll (keeps the denormalized {@link #itemCount} current). */
    public void addItems(int count) {
        this.itemCount += count;
        this.lastItemAt = Instant.now();
    }

    /** Mark the pass complete. Only an OPEN snapshot seals — a re-seal or a sealed/interrupted snapshot is a no-op. */
    public void seal() {
        if (status != SnapshotStatus.OPEN) {
            return;
        }
        this.status = SnapshotStatus.SEALED;
        this.sealedAt = Instant.now();
    }

    /**
     * A person stopped the scrape that was filling this pass.
     *
     * <p>Only an OPEN pass can be cancelled: one already sealed finished before the stop arrived, and saying
     * otherwise would rewrite a completed pass as an abandoned one on the strength of a message that lost a
     * race. A stop that arrives too late has nothing to cancel, which is the right outcome.</p>
     */
    public void cancel() {
        if (status != SnapshotStatus.OPEN) {
            return;
        }
        this.status = SnapshotStatus.CANCELLED;
        this.sealedAt = Instant.now();
    }

    /**
     * An item arrived for a pass the sweeper had given up on — so it was never abandoned.
     *
     * <p><strong>An inference must yield to evidence.</strong> INTERRUPTED is a GUESS: nobody said the pass
     * ended, a sweeper noticed it had gone quiet and drew a conclusion. An append is proof of the opposite,
     * and proof beats a guess. On 23/09/2026 the guess was terminal instead, and a live six-hour scrape was
     * swept four hours in — every page after that refused with a 409, its final seal a no-op, and 712 posts
     * of real work discarded because a pause outlasted a timer.</p>
     *
     * <p>Only INTERRUPTED reopens. SEALED, CANCELLED and REJECTED were all DECIDED — by the client finishing,
     * by a person stopping it, by a fingerprint that failed validation — and a late arrival must not overturn
     * somebody's decision. It is only the guess that gives way.</p>
     */
    void reopenBecauseItIsStillAlive() {
        if (status != SnapshotStatus.INTERRUPTED) {
            return;
        }
        this.status = SnapshotStatus.OPEN;
        this.sealedAt = null;
    }

    /** GC sweep (P1): abandon an orphaned OPEN pass. Only ever called on OPEN snapshots (the sweeper filters them). */
    public void interrupt() {
        this.status = SnapshotStatus.INTERRUPTED;
        this.sealedAt = Instant.now();
    }

    /**
     * Void this pass permanently — nothing it streamed may ever be cut into a reference. Terminal: a rejected
     * snapshot cannot be sealed, and {@link #isUsable()} answers false for every one of its items.
     */
    public void reject(String reason) {
        this.status = SnapshotStatus.REJECTED;
        this.rejectedReason = reason;
    }

    public String getRejectedReason() {
        return rejectedReason;
    }
}
