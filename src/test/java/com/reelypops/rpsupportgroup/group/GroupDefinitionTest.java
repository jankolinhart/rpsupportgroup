package com.reelypops.rpsupportgroup.group;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GroupDefinition#canonicalized()} — single-marker day-offset derivation (R6) + the existing CONTINUOUS
 * day-sync. Offsets are DERIVED from the times the operator enters: liking-end is the first likes-until at/after the
 * NEXT marker (grace), safe-removal strictly after the liking-end.
 */
class GroupDefinitionTest {

    /** A SINGLE_MARKER definition with the three times + explicit (usually null) offsets. */
    private static GroupDefinition single(String marker, String likes, Integer likesOff, String remove, Integer removeOff) {
        return new GroupDefinition(SgType.SINGLE_MARKER, "Europe/Paris", List.of("owner"), 1, null,
                null, null, null, marker, likes, likesOff, remove, removeOff, List.of());
    }

    @Test
    void singleMarker_likesAfterMarker_removeBeforeLikes_derivesDay1andDay2() {
        // M 19:30 → next marker day 1; L 23:59 ≥ M → liking-end day 1 (grace); R 10:30 ≤ L → safe-remove day 2.
        GroupDefinition d = single("19:30", "23:59", null, "10:30", null).canonicalized();
        assertThat(d.likesUntilDayOffset()).isEqualTo(1);
        assertThat(d.tagRemoveEarliestDayOffset()).isEqualTo(2);
    }

    @Test
    void singleMarker_removeLaterInDayThanLikes_staysSameDayAsLikingEnd() {
        // L 10:00 ≥ M 08:00 → day 1; R 14:00 > L → same day as liking-end (day 1).
        GroupDefinition d = single("08:00", "10:00", null, "14:00", null).canonicalized();
        assertThat(d.likesUntilDayOffset()).isEqualTo(1);
        assertThat(d.tagRemoveEarliestDayOffset()).isEqualTo(1);
    }

    @Test
    void singleMarker_likesBeforeMarker_crossesMidnight_derivesDay2() {
        // L 02:00 < M 23:00 → liking-end crosses midnight past the next marker → day 2; R 10:30 > L → same day (2).
        GroupDefinition d = single("23:00", "02:00", null, "10:30", null).canonicalized();
        assertThat(d.likesUntilDayOffset()).isEqualTo(2);
        assertThat(d.tagRemoveEarliestDayOffset()).isEqualTo(2);
    }

    @Test
    void singleMarker_unparseableMarker_fallsBackToExplicitOffsets() {
        // M blank → liking-end can't be derived → keep explicit likesOff (null) → remove also can't derive (likesOff
        // null) → keep explicit removeOff. Covers the blank + null-likesOff fallbacks.
        GroupDefinition d = single("", "23:59", null, "10:30", 7).canonicalized();
        assertThat(d.likesUntilDayOffset()).isNull();
        assertThat(d.tagRemoveEarliestDayOffset()).isEqualTo(7);
    }

    @Test
    void singleMarker_nullMarkerTime_fallsBackToExplicitLikesOffset() {
        // M null → likesOff can't derive → keeps explicit 4; remove still derives strictly after that (R 10:30 ≤
        // L 23:59 → likesOff+1 = 5).
        GroupDefinition d = single(null, "23:59", 4, "10:30", 9).canonicalized();
        assertThat(d.likesUntilDayOffset()).isEqualTo(4);
        assertThat(d.tagRemoveEarliestDayOffset()).isEqualTo(5);
    }

    @Test
    void singleMarker_timeWithoutColon_isUnparseable_fallsBack() {
        GroupDefinition d = single("1930", "2359", 3, "1030", 5).canonicalized();
        assertThat(d.likesUntilDayOffset()).isEqualTo(3);
        assertThat(d.tagRemoveEarliestDayOffset()).isEqualTo(5);
    }

    @Test
    void singleMarker_nonNumericTime_isUnparseable_fallsBack() {
        // Marker unparseable → likesOff keeps explicit 3; remove derives strictly after it (R 10:30 ≤ L 23:59 → 4).
        GroupDefinition d = single("ab:cd", "23:59", 3, "10:30", 5).canonicalized();
        assertThat(d.likesUntilDayOffset()).isEqualTo(3);
        assertThat(d.tagRemoveEarliestDayOffset()).isEqualTo(4);
    }

    @Test
    void singleMarker_outOfRangeHourOrMinute_isUnparseable_fallsBack() {
        assertThat(single("25:00", "23:59", 3, "10:30", 5).canonicalized().likesUntilDayOffset()).isEqualTo(3);
        assertThat(single("19:30", "10:70", 3, "10:30", 5).canonicalized().likesUntilDayOffset()).isEqualTo(3);
    }

    @Test
    void singleMarker_validLikes_butUnparseableRemove_keepsExplicitRemoveOffset() {
        // Liking-end derives (day 1) but the remove time is unparseable → remove offset falls back to explicit.
        GroupDefinition d = single("08:00", "10:00", null, "", 6).canonicalized();
        assertThat(d.likesUntilDayOffset()).isEqualTo(1);
        assertThat(d.tagRemoveEarliestDayOffset()).isEqualTo(6);
    }

    @Test
    void continuous_backfillsOpenWeekdaysFromContinuousDays() {
        GroupDefinition d = new GroupDefinition(SgType.CONTINUOUS, "UTC", List.of(), 1, List.of(1, 2, 3),
                null, null, null, null, null, null, null, null, null).canonicalized();
        assertThat(d.openWeekdays()).containsExactly(1, 2, 3);
        assertThat(d.continuousDays()).containsExactly(1, 2, 3);
    }

    @Test
    void continuous_prefersExplicitOpenWeekdays() {
        GroupDefinition d = new GroupDefinition(SgType.CONTINUOUS, "UTC", List.of(), 1, List.of(0),
                null, null, null, null, null, null, null, null, List.of(5, 6)).canonicalized();
        assertThat(d.continuousDays()).containsExactly(5, 6);
    }

    @Test
    void twoMarker_isReturnedUnchanged() {
        GroupDefinition base = new GroupDefinition(SgType.TWO_MARKER, "UTC", List.of("o"), 1, null,
                "09:00", "22:00", 0, null, "23:00", 4, "08:00", 9, List.of(1));
        assertThat(base.canonicalized()).isSameAs(base);
    }
}
