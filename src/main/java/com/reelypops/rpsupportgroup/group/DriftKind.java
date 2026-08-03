package com.reelypops.rpsupportgroup.group;

/** The kind of client-reported drift recorded as a {@link DriftObservation} (M5 re-vet consumer). */
public enum DriftKind {

    /** A persistent marker-detection disagreement (the client's per-scan agree/disagree tally) — derives "needs re-vet". */
    MARKER_DISAGREE,

    /** A client-nominated candidate marker owner (a new-owner drift) — the admin review-candidate surface. */
    NEW_OWNER
}
