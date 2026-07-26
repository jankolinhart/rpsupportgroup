package com.reelypops.rpsupportgroup.group;

/**
 * The group's round structure as <em>derived</em> from the marker clusters (M3b, advisory) — how many marker slots the
 * accepted owner posts each round. Kept in the {@code group} package (like {@link MarkerStyle}) so the persisted
 * {@link DetectedProfile} carries no dependency back into the vetting pipeline.
 *
 * <p>Only {@code SINGLE_MARKER} and {@code TWO_MARKER} are derivable from markers; a {@code CONTINUOUS} group has no
 * markers to cluster, so it is never proposed here — its always-open nature is a config choice, not a detection.
 */
public enum MarkerGroupType {

    /** One marker per round (a single boundary post) — always-open, the marker only delimits transitions. */
    SINGLE_MARKER,

    /** Two markers per round (a START and an END post) — the pair bounds an open window each round. */
    TWO_MARKER,

    /** Not enough evidence to say (no clean owner, ambiguous cluster count, or too few rounds). */
    UNKNOWN
}
