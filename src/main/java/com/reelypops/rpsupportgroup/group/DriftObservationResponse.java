package com.reelypops.rpsupportgroup.group;

import java.time.Instant;
import java.util.UUID;

/** A drift observation as returned to the admin (M5): the reporter, latest tally, and how-often timeline. */
public record DriftObservationResponse(
        UUID id,
        DriftKind kind,
        String reporterDeviceId,
        UUID reporterUserId,
        String nominatedOwnerHandle,
        Integer agreePass,
        Integer disagreePass,
        Integer persistenceCount,
        long occurrenceCount,
        boolean resolved,
        Instant firstSeenAt,
        Instant lastSeenAt,
        String markerRole,
        /** Which reference, not just which role — the admin prompt names the banner the picture belongs to. */
        String markerText,
        /** For a MARKER_REFERENCE_CORRUPT observation: the malformed value and why it is malformed. */
        String detail,
        Integer imageDistance,
        Integer imageThreshold,
        String evidencePostId,
        /** Fetch the picture at {@code GET /supportgroup/v1/internal/groups/marker-images/{locator}}. */
        String evidenceImageLocator) {

    static DriftObservationResponse of(DriftObservation o) {
        return new DriftObservationResponse(o.getId(), o.getKind(), o.getReporterDeviceId(), o.getReporterUserId(),
                o.getNominatedOwnerHandle(), o.getAgreePass(), o.getDisagreePass(), o.getPersistenceCount(),
                o.getOccurrenceCount(), o.isResolved(), o.getFirstSeenAt(), o.getLastSeenAt(),
                o.getMarkerRole(), o.getMarkerText(), o.getDetail(), o.getImageDistance(), o.getImageThreshold(),
                o.getEvidencePostId(), o.getEvidenceImageLocator());
    }
}
