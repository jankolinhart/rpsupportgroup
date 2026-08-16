package com.reelypops.rpsupportgroup.group;

/** The kind of client-reported drift recorded as a {@link DriftObservation} (M5 re-vet consumer). */
public enum DriftKind {

    /** A persistent marker-detection disagreement (the client's per-scan agree/disagree tally) — derives "needs re-vet". */
    MARKER_DISAGREE,

    /** A client-nominated candidate marker owner (a new-owner drift) — the admin review-candidate surface. */
    NEW_OWNER,

    /**
     * MEASURED banner drift (16/08/2026): the marker was CONFIRMED by its text, and its picture sits
     * {@code imageDistance} bits from the reference that names it — beyond that reference's own tolerance.
     *
     * <p>Semantically this is NOT a failure and the admin surface must not read like one. Nothing was missed and no
     * marker was lost: the owner re-screenshotted or re-exported the same banner, so the picture is a newer version
     * of itself and the vetted reference is simply out of date. The remedy is to ADD the new picture to the
     * reference, never to replace what is there.</p>
     *
     * <p>Distinct from {@link #MARKER_DISAGREE}, which counts DEMOTIONS: that tally over-reports the owner's
     * ordinary non-marker posts and falls silent once text decides the role, since a rescued marker is never
     * demoted.</p>
     */
    MARKER_IMAGE_DRIFT
}
