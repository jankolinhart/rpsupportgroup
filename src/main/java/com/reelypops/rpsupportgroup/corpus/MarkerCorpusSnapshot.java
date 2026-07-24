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

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sealed_at")
    private Instant sealedAt;

    private MarkerCorpusSnapshot(String igAccount, CorpusSource source, String capturedByAccount) {
        this.id = UUID.randomUUID();
        this.igAccount = igAccount;
        this.source = source;
        this.capturedByAccount = capturedByAccount;
        this.status = SnapshotStatus.OPEN;
        this.itemCount = 0;
    }

    /** Open a new snapshot to stream a deep pass into. */
    public static MarkerCorpusSnapshot open(String igAccount, CorpusSource source, String capturedByAccount) {
        return new MarkerCorpusSnapshot(igAccount, source, capturedByAccount);
    }

    /** Record that {@code count} items were appended this scroll (keeps the denormalized {@link #itemCount} current). */
    public void addItems(int count) {
        this.itemCount += count;
    }

    /** Mark the pass complete. Only an OPEN snapshot seals — a re-seal or a sealed/interrupted snapshot is a no-op. */
    public void seal() {
        if (status != SnapshotStatus.OPEN) {
            return;
        }
        this.status = SnapshotStatus.SEALED;
        this.sealedAt = Instant.now();
    }

    /** GC sweep (P1): abandon an orphaned OPEN pass. Only ever called on OPEN snapshots (the sweeper filters them). */
    public void interrupt() {
        this.status = SnapshotStatus.INTERRUPTED;
        this.sealedAt = Instant.now();
    }
}
