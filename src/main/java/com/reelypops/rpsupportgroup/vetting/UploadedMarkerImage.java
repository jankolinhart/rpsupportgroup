package com.reelypops.rpsupportgroup.vetting;

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
 * A marker image an operator uploaded by hand in the Vetting Portal (M3 follow-up) when the auto-detected corpus
 * missed a marker. We keep the raw bytes plus a best-effort perceptual {@code dHash} (the same 9x8 horizontal-gradient
 * format the scraper emits) so a vetted profile can reference the upload just like a detected cluster. Standalone —
 * not tied to any single scrape snapshot.
 */
@Entity
@Table(name = "sg_uploaded_marker_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadedMarkerImage {

    @Id
    private UUID id;

    @Column(name = "d_hash", nullable = false, updatable = false)
    private String dHash;

    @Column(name = "image", nullable = false, updatable = false)
    private byte[] image;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Getter(AccessLevel.NONE) // audit-only column (queryable in SQL); no Java reader needed
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private UploadedMarkerImage(String dHash, byte[] image, String contentType) {
        this.id = UUID.randomUUID();
        this.dHash = dHash;
        this.image = image;
        this.contentType = contentType;
    }

    /** Store a freshly uploaded marker image with its computed perceptual hash. */
    public static UploadedMarkerImage create(String dHash, byte[] image, String contentType) {
        return new UploadedMarkerImage(dHash, image, contentType);
    }
}
