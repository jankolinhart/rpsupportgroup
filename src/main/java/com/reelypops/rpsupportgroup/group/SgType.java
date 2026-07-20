package com.reelypops.rpsupportgroup.group;

/** Support-group round-boundary type (§3): how a liking round opens and closes. */
public enum SgType {

    /** One marker post closes the current round and opens the next. */
    SINGLE_MARKER,

    /** Distinct start + end markers (usually a pair); the end closes, the start opens the next. */
    TWO_MARKER,

    /** No markers — a new day (in the group's timezone) is a new round. */
    CONTINUOUS
}
