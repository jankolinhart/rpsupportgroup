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
        String evidenceImageLocator,
        /**
         * The REFERENCE's own stored picture — the vetted one, which a repair recomputes its hash from.
         *
         * <p>Shown beside {@code evidenceImageLocator} (the LIVE banner the client captured) so an administrator
         * compares the two pictures directly instead of taking our word for which is which.</p>
         */
        String referenceImageLocator,
        /** The hash the stored picture really produces — exactly what a repair would write. */
        String referenceImageHash,
        /** The hash the live banner produces — exactly what adopting it would write. */
        String evidenceImageHash,
        /**
         * What the reference stores TODAY.
         *
         * <p>For a corrupt reference this is the value that is not a hash. Printed next to the two real hashes,
         * the fault needs no explaining: 39 characters of base64url beside two rows of 64 binary digits.</p>
         */
        String storedValue,
        /** Bits between the live banner and the vetted picture — 0 means adding the live one buys nothing. */
        Integer liveDistance) {

    static DriftObservationResponse of(SupportGroupConfigService.ReferenceDriftView v) {
        return of(v.observation(), v.referenceImageLocator(), v.referenceImageHash(), v.evidenceImageHash(),
                v.storedValue(), v.liveDistance());
    }

    static DriftObservationResponse of(DriftObservation o) {
        return of(o, null, null, null, null, null);
    }

    static DriftObservationResponse of(DriftObservation o, String referenceImageLocator, String referenceImageHash,
                                       String evidenceImageHash, String storedValue, Integer liveDistance) {
        return new DriftObservationResponse(o.getId(), o.getKind(), o.getReporterDeviceId(), o.getReporterUserId(),
                o.getNominatedOwnerHandle(), o.getAgreePass(), o.getDisagreePass(), o.getPersistenceCount(),
                o.getOccurrenceCount(), o.isResolved(), o.getFirstSeenAt(), o.getLastSeenAt(),
                o.getMarkerRole(), o.getMarkerText(), o.getDetail(), o.getImageDistance(), o.getImageThreshold(),
                o.getEvidencePostId(), o.getEvidenceImageLocator(), referenceImageLocator, referenceImageHash,
                evidenceImageHash, storedValue, liveDistance);
    }
}
