package com.reelypops.rpsupportgroup.group;

/**
 * The round CLASS of a TWO_MARKER schedule (M5 A3): whether a round opens + closes on the SAME group-local day
 * ({@link #INTRA_DAY}) or spans midnight into a later day ({@link #CROSS_DAY}). Derived purely from the end marker's
 * day offset — {@code endMarkerDayOffset >= 1 => CROSS_DAY} — so it can never drift from the schedule it describes.
 *
 * <p>It governs which day is the ANCHOR for an observed END marker + the expected-end window the client uses for drift
 * and missing-marker robustness (vision §5b, plan "M5 round rule"). Only meaningful for TWO_MARKER: SINGLE_MARKER and
 * CONTINUOUS have no cross-midnight anchor question, so the class is {@code null} for them.
 */
public enum RoundClass {

    /** The round opens + closes on the same group-local day (end-marker day offset 0 / unset). */
    INTRA_DAY,

    /** The round's end marker lands one or more group-local days after the start (offset >= 1) — it spans midnight. */
    CROSS_DAY;

    /**
     * The round class for a schedule from its end-marker day offset, or {@code null} when {@code twoMarker} is false
     * (SINGLE_MARKER / CONTINUOUS / unknown carry no class). {@code CROSS_DAY} iff {@code endMarkerDayOffset >= 1}.
     */
    public static RoundClass of(boolean twoMarker, Integer endMarkerDayOffset) {
        if (!twoMarker) {
            return null;
        }
        return endMarkerDayOffset != null && endMarkerDayOffset >= 1 ? CROSS_DAY : INTRA_DAY;
    }
}
