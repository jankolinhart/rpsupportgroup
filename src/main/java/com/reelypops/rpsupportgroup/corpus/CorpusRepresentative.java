package com.reelypops.rpsupportgroup.corpus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A representative thumbnail for one post within a {@link MarkerCorpusSnapshot} (P1, slice 3b): the actual image bytes
 * that Tier-2 vision / OCR reads for a cluster representative — a handful of posts per snapshot (most items are
 * metadata + dHash only). Keyed by (snapshot, post shortcode) and upserted. Kept in its own table (avatar pattern) so
 * the metadata item reads never drag image bytes.
 */
@Entity
@Table(name = "sg_corpus_representative")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CorpusRepresentative {

    @Id
    private UUID id;

    @Column(name = "snapshot_id", nullable = false, updatable = false)
    private UUID snapshotId;

    @Column(name = "shortcode", nullable = false, updatable = false)
    private String shortcode;

    @Column(name = "image", nullable = false)
    private byte[] image;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private CorpusRepresentative(UUID snapshotId, String shortcode, byte[] image, String contentType) {
        this.id = UUID.randomUUID();
        this.snapshotId = snapshotId;
        this.shortcode = shortcode;
        this.image = image;
        this.contentType = contentType;
    }

    /** Create the first representative thumbnail for a post in a snapshot. */
    public static CorpusRepresentative create(UUID snapshotId, String shortcode, byte[] image, String contentType) {
        return new CorpusRepresentative(snapshotId, shortcode, image, contentType);
    }

    /** Replace the stored thumbnail (a re-scrape contributes fresher bytes). */
    public void update(byte[] image, String contentType) {
        this.image = image;
        this.contentType = contentType;
    }
}
