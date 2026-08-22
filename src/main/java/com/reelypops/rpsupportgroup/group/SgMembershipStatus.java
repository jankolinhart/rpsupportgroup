package com.reelypops.rpsupportgroup.group;

/**
 * The stored follow state of one user against one of a support group's Instagram accounts (B6). Only two states
 * are ever persisted — a positive {@link #FOLLOWING} confirmation or an explicit {@link #NOT_FOLLOWING} report.
 *
 * <p>There is deliberately no {@code UNKNOWN} / {@code REQUESTED} state: an inconclusive report is fail-open (it
 * never mutates a stored status and never inserts a row), so the record only ever holds a definite signal. The
 * client's suppression predicate keys off exactly {@link #NOT_FOLLOWING}.</p>
 */
public enum SgMembershipStatus {
    FOLLOWING,
    NOT_FOLLOWING
}
