package com.reelypops.rpsupportgroup.group;

/**
 * The current round state read from the group's <em>trailing</em> marker (M3b, advisory): the newest marker tells us
 * whether a round is open right now, not the newest post's position. Kept in the {@code group} package (like
 * {@link MarkerStyle}) so the persisted {@link DetectedProfile} carries no dependency into the vetting pipeline.
 */
public enum RoundState {

    /** The trailing marker is a START (or the single marker) with no END after it — a round is currently open. */
    OPEN,

    /** The trailing marker is an END with no START after it — the group is in a closed period between rounds. */
    CLOSED_PERIOD,

    /** No usable trailing marker (no owner, no marker times) — the state cannot be read. */
    UNKNOWN
}
