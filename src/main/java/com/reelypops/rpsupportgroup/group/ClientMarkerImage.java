package com.reelypops.rpsupportgroup.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A marker picture a CLIENT delivered, kept durably so it can be chosen during a later vetting (18/08/2026).
 *
 * <p><strong>Why it exists.</strong> Drift observations are resolved and eventually pruned — they are a work
 * queue, not a record. But the picture a client captured is evidence with a long life: it is the only proof of
 * what a marker looked like on a given day, and Directive B1 means no cloud service can ever go and fetch it
 * again. Losing it means the only way back is a full duty scrape.</p>
 *
 * <p><strong>Why the hash is the client's.</strong> Measured 16/08/2026, this service's own {@code ImageDHash}
 * lands <strong>15–34 bits</strong> from the client's for identical bytes — as far apart as unrelated images —
 * while clients match live posts at a 4–10 bit tolerance. So a fingerprint computed here would look perfectly
 * healthy and never match anything. Cross-PLATFORM agreement is proven (0 bits across ubuntu/windows/macos with
 * real Instagram images); it is crossing IMPLEMENTATIONS that breaks. Every hash stored here came from a client
 * and is stored verbatim.</p>
 *
 * <p>Deduplicated by {@code (config, dHash)}: the same banner seen on fifty scans is one candidate, not fifty —
 * {@code timesSeen} carries how often, which is a useful signal of how established a rendition is.</p>
 */
@Entity
@Table(name = "client_marker_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClientMarkerImage {

    @Id
    private UUID id;

    @Column(name = "config_id", nullable = false, updatable = false)
    private UUID configId;

    /** The CLIENT's fingerprint of this picture. Never recomputed here — see the class note. */
    @Column(name = "d_hash", nullable = false, updatable = false, length = 64)
    private String dHash;

    @Column(name = "image_locator", nullable = false, length = 128)
    private String imageLocator;

    @Column(name = "marker_role", length = 20)
    private String markerRole;

    @Column(name = "marker_text", length = 512)
    private String markerText;

    @Column(name = "evidence_post_id", length = 64)
    private String evidencePostId;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "times_seen", nullable = false)
    private long timesSeen;

    private ClientMarkerImage(UUID configId, String dHash, String imageLocator, String markerRole, String markerText,
                              String evidencePostId, Instant now) {
        this.id = UUID.randomUUID();
        this.configId = configId;
        this.dHash = dHash;
        this.imageLocator = imageLocator;
        this.markerRole = markerRole;
        this.markerText = markerText;
        this.evidencePostId = evidencePostId;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
        this.timesSeen = 1L;
    }

    static ClientMarkerImage first(UUID configId, String dHash, String imageLocator, String markerRole,
                                   String markerText, String evidencePostId, Instant now) {
        return new ClientMarkerImage(configId, dHash, imageLocator, markerRole, markerText, evidencePostId, now);
    }

    /**
     * Seen again — bump the tally and refresh what we know about it.
     *
     * <p>The role, text and post are refreshed because the LATEST sighting is the most useful description; the
     * hash never changes, since it is the identity of the row.</p>
     */
    void seenAgain(String markerRole, String markerText, String evidencePostId, Instant now) {
        if (markerRole != null && !markerRole.isBlank()) {
            this.markerRole = markerRole;
        }
        if (markerText != null && !markerText.isBlank()) {
            this.markerText = markerText;
        }
        if (evidencePostId != null && !evidencePostId.isBlank()) {
            this.evidencePostId = evidencePostId;
        }
        this.lastSeenAt = now;
        this.timesSeen += 1L;
    }
}
