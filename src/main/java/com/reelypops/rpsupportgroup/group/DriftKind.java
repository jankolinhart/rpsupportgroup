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
    MARKER_IMAGE_DRIFT,

    /**
     * A vetted reference whose stored dHash is <strong>not a perceptual hash at all</strong> — found by the client
     * while reading the profile it was shipped (16/08/2026).
     *
     * <p>This is a DATA FAULT, not drift, and it is worse than drift because it is <em>silent</em>: every comparison
     * site skips a hash whose length differs from the candidate's, so a malformed reference can never match, never
     * contradict, and never takes part in the client's threshold calibration. `glowbloggeragency` carried a 39-char
     * Instagram post shortcode where its Sunday START hash belongs, and it went unnoticed until a misread marker cost
     * a day of likes.</p>
     *
     * <p>Ingest validation now refuses to accept one (so no NEW profile can carry it), but a profile stored BEFORE
     * that guard keeps its fault until someone repairs it. This kind is how the cloud finds out: the client is the
     * only party that reads every reference on every scan.</p>
     *
     * <p>The remedy is different from {@link #MARKER_IMAGE_DRIFT}'s. Nothing needs to be captured from Instagram —
     * the reference's own picture is already stored against its {@code imageLocator}, so the hash is
     * <strong>recomputable in place</strong>. See
     * {@code SupportGroupConfigService#repairMalformedReferenceHashes}.</p>
     */
    MARKER_REFERENCE_CORRUPT
}
