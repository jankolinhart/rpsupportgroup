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
        Integer imageDistance,
        Integer imageThreshold,
        String evidencePostId,
        /** Fetch the picture at {@code GET /supportgroup/v1/internal/groups/marker-images/{locator}}. */
        String evidenceImageLocator) {

    static DriftObservationResponse of(DriftObservation o) {
        return new DriftObservationResponse(o.getId(), o.getKind(), o.getReporterDeviceId(), o.getReporterUserId(),
                o.getNominatedOwnerHandle(), o.getAgreePass(), o.getDisagreePass(), o.getPersistenceCount(),
                o.getOccurrenceCount(), o.isResolved(), o.getFirstSeenAt(), o.getLastSeenAt(),
                o.getMarkerRole(), o.getImageDistance(), o.getImageThreshold(), o.getEvidencePostId(),
                o.getEvidenceImageLocator());
    }
}
