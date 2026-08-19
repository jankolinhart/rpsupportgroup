package com.reelypops.rpsupportgroup.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One reporter's running LEDGER of a single drift for a config (M5 re-vet consumer). Upserted per
 * (config, kind, reporter [+ nominated handle]): the first report inserts the row (occurrence_count = 1); each later
 * report for the same natural key bumps occurrence_count + last_seen_at and refreshes the latest tally. An unresolved
 * {@link DriftKind#MARKER_DISAGREE} observation DERIVES the config's "needs re-vet" flag (a read-side signal — no
 * config mutation); a {@link DriftKind#NEW_OWNER} observation is an admin review-candidate nomination.
 */
@Entity
@Table(name = "drift_observation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DriftObservation {

    @Id
    private UUID id;

    @Column(name = "config_id", nullable = false, updatable = false)
    private UUID configId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 40)
    private DriftKind kind;

    @Column(name = "reporter_device_id", nullable = false, updatable = false, length = 128)
    private String reporterDeviceId;

    @Column(name = "reporter_user_id", updatable = false)
    private UUID reporterUserId;

    @Column(name = "nominated_owner_handle", updatable = false, length = 128)
    private String nominatedOwnerHandle;

    @Column(name = "agree_pass")
    private Integer agreePass;

    @Column(name = "disagree_pass")
    private Integer disagreePass;

    @Column(name = "persistence_count")
    private Integer persistenceCount;

    /**
     * The MEASURED drift (16/08/2026) — nullable, because a MARKER_DISAGREE / NEW_OWNER row has no measurement.
     * {@code imageDistance} is the Hamming distance from the confirmed marker's picture to the reference that NAMES
     * it, and {@code imageThreshold} that reference's own tolerance. {@code evidenceImageLocator} points at the
     * picture the owner is actually posting, uploaded by the CLIENT (directive B1 — no cloud service ever contacts
     * Instagram), which is what makes a re-vet possible without a full duty scrape.
     */
    @Column(name = "marker_role", length = 20)
    private String markerRole;

    /**
     * WHICH weekday slot (JS 0=Sun … 6=Sat) the drifting reference belongs to — resolved by the CLIENT from the
     * marker's own postedOn through the schedule's day-offsets, {@code null} for a flat/legacy group or an old
     * client. Part of the natural key ({@code uq_drift_obs_natural}) and {@code updatable = false} like the rest
     * of it: the same role+text on another day is a DIFFERENT observation, because the per-weekday profile makes
     * it a different reference. Adoption writes into exactly this day's slot and no other.
     */
    @Column(name = "marker_weekday", updatable = false)
    private Integer markerWeekday;

    @Column(name = "image_distance")
    private Integer imageDistance;

    @Column(name = "image_threshold")
    private Integer imageThreshold;

    @Column(name = "evidence_post_id", length = 64)
    private String evidencePostId;

    @Column(name = "evidence_image_locator", length = 128)
    private String evidenceImageLocator;

    /**
     * The OCR text of the reference this drift was measured against — the only thing that distinguishes two
     * references sharing a role. A per-weekday group carries several banners per role, so adoption without it could
     * only match by role and would teach every weekday the one day's picture.
     */
    @Column(name = "marker_text", length = 512)
    private String markerText;

    /** For {@link DriftKind#MARKER_REFERENCE_CORRUPT}: the malformed value and why it is malformed. */
    @Column(name = "detail", length = 1024)
    private String detail;

    /**
     * THE CLIENT'S OWN fingerprint of {@code evidenceImageLocator}'s picture — stored verbatim, never recomputed.
     *
     * <p>This service's {@code ImageDHash} lands 15–34 bits away for identical bytes (measured 16/08/2026), which
     * is as far apart as unrelated images, while clients match at a 4–10 bit tolerance. A hash computed here would
     * therefore look perfectly healthy and never match anything. Cross-PLATFORM agreement is proven (0 bits on
     * ubuntu/windows/macos); crossing IMPLEMENTATIONS is what breaks.</p>
     */
    @Column(name = "evidence_image_hash", length = 64)
    private String evidenceImageHash;

    @Column(name = "occurrence_count", nullable = false)
    private long occurrenceCount;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    private DriftObservation(UUID configId, DriftKind kind, String reporterDeviceId, UUID reporterUserId,
                             String nominatedOwnerHandle, Integer markerWeekday, Integer agreePass,
                             Integer disagreePass, Integer persistenceCount, Instant now) {
        this.id = UUID.randomUUID();
        this.configId = configId;
        this.kind = kind;
        this.reporterDeviceId = reporterDeviceId;
        this.reporterUserId = reporterUserId;
        this.nominatedOwnerHandle = nominatedOwnerHandle;
        this.markerWeekday = markerWeekday;
        this.agreePass = agreePass;
        this.disagreePass = disagreePass;
        this.persistenceCount = persistenceCount;
        this.occurrenceCount = 1L;
        this.resolved = false;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    /** The first report from a reporter for this (config, kind [, handle]) — occurrence 1. */
    static DriftObservation first(UUID configId, DriftKind kind, String reporterDeviceId, UUID reporterUserId,
                                  String nominatedOwnerHandle, Integer agreePass, Integer disagreePass,
                                  Integer persistenceCount, Instant now) {
        return first(configId, kind, reporterDeviceId, reporterUserId, nominatedOwnerHandle, null,
                agreePass, disagreePass, persistenceCount, now);
    }

    /** As above, carrying the WEEKDAY SLOT the drifting reference belongs to (part of the natural key). */
    static DriftObservation first(UUID configId, DriftKind kind, String reporterDeviceId, UUID reporterUserId,
                                  String nominatedOwnerHandle, Integer markerWeekday, Integer agreePass,
                                  Integer disagreePass, Integer persistenceCount, Instant now) {
        return new DriftObservation(configId, kind, reporterDeviceId, reporterUserId, nominatedOwnerHandle,
                markerWeekday, agreePass, disagreePass, persistenceCount, now);
    }

    /**
     * A later report for the same natural key: bump the occurrence count, refresh the latest tally, re-open a resolved
     * row (a fresh drift after a re-vet re-raises the flag), and advance last-seen.
     */
    void observeAgain(Integer agreePass, Integer disagreePass, Integer persistenceCount, Instant now) {
        this.occurrenceCount += 1L;
        this.agreePass = agreePass;
        this.disagreePass = disagreePass;
        this.persistenceCount = persistenceCount;
        this.resolved = false;
        this.lastSeenAt = now;
    }

    /** Attach / refresh the MEASURED drift and the picture behind it. */
    void measure(String markerRole, Integer imageDistance, Integer imageThreshold, String evidencePostId,
                 String evidenceImageLocator) {
        measure(markerRole, null, imageDistance, imageThreshold, evidencePostId, evidenceImageLocator, null);
    }

    /** As above, carrying the CLIENT's fingerprint of the delivered picture. */
    void measure(String markerRole, Integer imageDistance, Integer imageThreshold, String evidencePostId,
                 String evidenceImageLocator, String evidenceImageHash) {
        measure(markerRole, null, imageDistance, imageThreshold, evidencePostId, evidenceImageLocator, null,
                evidenceImageHash);
    }

    /** As above, naming the exact reference ({@code markerText}) and, for a corrupt reference, the fault. */
    void measure(String markerRole, String markerText, Integer imageDistance, Integer imageThreshold,
                 String evidencePostId, String evidenceImageLocator, String detail) {
        measure(markerRole, markerText, imageDistance, imageThreshold, evidencePostId, evidenceImageLocator, detail,
                null);
    }

    /** As above, carrying the CLIENT's fingerprint of the delivered picture. */
    void measure(String markerRole, String markerText, Integer imageDistance, Integer imageThreshold,
                 String evidencePostId, String evidenceImageLocator, String detail, String evidenceImageHash) {
        this.markerRole = markerRole;
        this.markerText = markerText;
        this.imageDistance = imageDistance;
        this.imageThreshold = imageThreshold;
        this.evidencePostId = evidencePostId;
        this.detail = detail;
        // Only replace the picture when a new one actually arrived: a report that could not carry the image must
        // not erase the evidence an earlier one delivered.
        if (evidenceImageLocator != null) {
            this.evidenceImageLocator = evidenceImageLocator;
            this.evidenceImageHash = evidenceImageHash; // the fingerprint belongs TO that picture — move together
        }
    }

    public String getMarkerRole() { return markerRole; }

    public String getMarkerText() { return markerText; }

    public String getDetail() { return detail; }

    public Integer getImageDistance() { return imageDistance; }

    public Integer getImageThreshold() { return imageThreshold; }

    public String getEvidencePostId() { return evidencePostId; }

    public String getEvidenceImageLocator() { return evidenceImageLocator; }

    public String getEvidenceImageHash() { return evidenceImageHash; }

    /** Mark this observation resolved (a re-vet cleared the derived marker-disagree flag). */
    void resolve() {
        this.resolved = true;
    }
}
