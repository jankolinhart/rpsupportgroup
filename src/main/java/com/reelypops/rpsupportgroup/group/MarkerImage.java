package com.reelypops.rpsupportgroup.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A durable, <strong>content-addressed</strong> marker DISPLAY image (sg-per-weekday-marker-images.md). Captured at
 * vet time from a marker reference's chosen image and keyed by the sha-256 hex of the (thumbnail) bytes — that hex is
 * the {@code imageLocator} carried on the vetted profile's marker references and fetched + cached by the desktop
 * client to show the canonical per-weekday marker. Content-addressed so identical images share one row and a changed
 * image gets a new locator (the client re-fetches only what changed); durable so it survives corpus-snapshot pruning.
 */
@Entity
@Table(name = "sg_marker_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarkerImage {

    @Id
    private String locator;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "image", nullable = false, updatable = false)
    private byte[] image;

    @Getter(AccessLevel.NONE) // audit-only column (queryable in SQL); no Java reader needed
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private MarkerImage(String locator, byte[] image, String contentType) {
        this.locator = locator;
        this.image = image;
        this.contentType = contentType;
    }

    /** Store an image under its content-hash {@code locator}. */
    public static MarkerImage create(String locator, byte[] image, String contentType) {
        return new MarkerImage(locator, image, contentType);
    }
}
