package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.group.DetectedProfile.AiDiscovery.WeeklySchedule.DaySchedule;
import com.reelypops.rpsupportgroup.group.DetectedProfile.ScheduleFacet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M5 A3 round-class derivation: {@code endMarkerDayOffset >= 1 => CROSS_DAY}, TWO_MARKER only, and its exposure as a
 * derived accessor on the Tier-0 {@link ScheduleFacet}, the Tier-1 per-weekday {@link DaySchedule}, and the vetted
 * per-weekday {@link DayDefinition} config slice.
 */
class RoundClassTest {

    @Test
    void ofDerivesCrossDayFromTheEndMarkerOffset() {
        assertThat(RoundClass.of(true, 1)).isEqualTo(RoundClass.CROSS_DAY);
        assertThat(RoundClass.of(true, 2)).isEqualTo(RoundClass.CROSS_DAY);
        assertThat(RoundClass.of(true, 0)).isEqualTo(RoundClass.INTRA_DAY);
        assertThat(RoundClass.of(true, null)).isEqualTo(RoundClass.INTRA_DAY); // unset offset = same-day
        assertThat(RoundClass.of(false, 3)).isNull();                          // not TWO_MARKER = no class
    }

    @Test
    void scheduleFacetExposesTheDerivedClass() {
        assertThat(facet(MarkerGroupType.TWO_MARKER, 1).roundClass()).isEqualTo(RoundClass.CROSS_DAY);
        assertThat(facet(MarkerGroupType.TWO_MARKER, 0).roundClass()).isEqualTo(RoundClass.INTRA_DAY);
        assertThat(facet(MarkerGroupType.SINGLE_MARKER, 1).roundClass()).isNull();
    }

    @Test
    void aiDayScheduleExposesTheDerivedClass() {
        assertThat(day("TWO_MARKER", true, 1).roundClass()).isEqualTo(RoundClass.CROSS_DAY);
        assertThat(day("TWO_MARKER", true, 0).roundClass()).isEqualTo(RoundClass.INTRA_DAY);
        assertThat(day("TWO_MARKER", false, 1).roundClass()).isNull();   // CLOSED weekday
        assertThat(day("SINGLE_MARKER", true, 1).roundClass()).isNull(); // not TWO_MARKER
    }

    @Test
    void dayDefinitionExposesTheDerivedClass() {
        assertThat(def(SgType.TWO_MARKER, true, 1).roundClass()).isEqualTo(RoundClass.CROSS_DAY);
        assertThat(def(SgType.TWO_MARKER, true, 0).roundClass()).isEqualTo(RoundClass.INTRA_DAY);
        assertThat(def(SgType.TWO_MARKER, false, 1).roundClass()).isNull();  // CLOSED weekday
        assertThat(def(SgType.CONTINUOUS, true, 1).roundClass()).isNull();   // not TWO_MARKER
    }

    private static ScheduleFacet facet(MarkerGroupType type, Integer endOffset) {
        return new ScheduleFacet(type, 0.9, null, null, null, List.of(), 0.0,
                RoundState.OPEN, endOffset, 0.0, 0.0, 0.0, 0, null, 0.0);
    }

    private static DaySchedule day(String groupType, boolean open, Integer endOffset) {
        return new DaySchedule("MON", open, groupType, "FLAT_BANNER", List.of(), "09:00", "17:00", endOffset, null, 2, 0.8);
    }

    private static DayDefinition def(SgType type, boolean open, Integer endOffset) {
        return new DayDefinition(1, open, type, "09:00", "17:00", endOffset, null, null,
                null, null, null, null, null, MarkerStyle.FLAT_BANNER, List.of());
    }
}
