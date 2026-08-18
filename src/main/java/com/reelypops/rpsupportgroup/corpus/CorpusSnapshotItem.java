package com.reelypops.rpsupportgroup.corpus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One tagged-grid post within a {@link MarkerCorpusSnapshot} (P1): the metadata + perceptual {@code dHash} that CV /
 * AI vetting reads. {@code ordinal} preserves the grid order (taggedAt — newest tag on top, directive P5), and
 * {@code authorUsername} is the marker-owner-detection input. Representative thumbnail bytes arrive in a later slice.
 */
@Entity
@Table(name = "sg_corpus_snapshot_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CorpusSnapshotItem {

    @Id
    private UUID id;

    @Column(name = "snapshot_id", nullable = false, updatable = false)
    private UUID snapshotId;

    @Column(name = "shortcode", nullable = false)
    private String shortcode;

    @Column(name = "author_username", nullable = false)
    private String authorUsername;

    @Column(name = "d_hash", nullable = false)
    private String dHash;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "unusable", nullable = false)
    private boolean unusable;

    @Column(name = "unusable_reason", length = 512)
    private String unusableReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private CorpusSnapshotItem(UUID snapshotId, String shortcode, String authorUsername, String dHash,
                              Instant postedAt, int ordinal) {
        this.id = UUID.randomUUID();
        this.snapshotId = snapshotId;
        this.shortcode = shortcode;
        this.authorUsername = authorUsername;
        this.dHash = dHash;
        this.postedAt = postedAt;
        this.ordinal = ordinal;
    }

    /** Create an item to append to a snapshot. */
    public static CorpusSnapshotItem of(UUID snapshotId, String shortcode, String authorUsername, String dHash,
                                        Instant postedAt, int ordinal) {
        return new CorpusSnapshotItem(snapshotId, shortcode, authorUsername, dHash, postedAt, ordinal);
    }

    /**
     * Burn this item: it may never again be offered as a marker-reference candidate. Used when its fingerprint is
     * not a fingerprint (a shortcode, a truncation, a hash from another implementation) — the row is kept as
     * evidence of what a client actually streamed, but selecting it would write an unmatchable reference into a
     * vetted profile, which is the fault this whole path exists to prevent.
     */
    public void markUnusable(String reason) {
        this.unusable = true;
        this.unusableReason = reason;
    }

    public boolean isUnusable() {
        return unusable;
    }

    public String getUnusableReason() {
        return unusableReason;
    }
}
