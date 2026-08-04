package com.reelypops.rpsupportgroup.group;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M4.5-e pre-M5 projection: {@link VettedProfile#withDerivedDefinition()} regenerates the flat
 * {@link GroupDefinition} + detector from the first OPEN day of a per-weekday {@link WeeklyScheduleDefinition}, and is a
 * no-op for a legacy flat profile — so today's client keeps working while the per-day truth accumulates for M5.
 */
class VettedProfileTest {

    private static GroupDefinition base() {
        return new GroupDefinition(SgType.TWO_MARKER, "Europe/Berlin", List.of("owner"), null, null,
                "00:00", "00:00", 0, null, null, null, null, null, null);
    }

    private static DayDefinition day(int weekday, boolean open, String start, String end, Integer maxTagged) {
        return new DayDefinition(weekday, open, SgType.TWO_MARKER, start, end, 0, null,
                "18:00", 0, "18:05", 0, maxTagged, MarkerStyle.TEXT_OVERLAY,
                List.of(new VettedProfile.TypedMarkerReference("start", List.of("hash"), "GB AGENCY START", 4,
                        "grid", "SC1", null)));
    }

    @Test
    void withDerivedDefinition_flattensTheFirstOpenDayAndOpenWeekdays() {
        WeeklyScheduleDefinition ws = new WeeklyScheduleDefinition(List.of(
                day(6, false, null, null, null),      // Sat CLOSED (precedes the first open day)
                day(1, true, "09:00", "17:00", 2),    // Mon OPEN — the representative day
                day(2, true, "10:00", "18:00", 3)));  // Tue OPEN
        VettedProfile in = new VettedProfile(base(), null, "blurb", ws);

        VettedProfile out = in.withDerivedDefinition();

        // Group-global fields come from the base definition …
        assertThat(out.definition().type()).isEqualTo(SgType.TWO_MARKER);
        assertThat(out.definition().timezone()).isEqualTo("Europe/Berlin");
        assertThat(out.definition().markerOwners()).containsExactly("owner");
        // … the schedule fields from the first OPEN day (Mon) …
        assertThat(out.definition().startMarkerTime()).isEqualTo("09:00");
        assertThat(out.definition().endMarkerTime()).isEqualTo("17:00");
        assertThat(out.definition().maxTaggedPosts()).isEqualTo(2);
        // … and openWeekdays = the OPEN days, sorted.
        assertThat(out.definition().openWeekdays()).containsExactly(1, 2);
        // The detector is the representative day's; the per-day truth is preserved.
        assertThat(out.detector().style()).isEqualTo(MarkerStyle.TEXT_OVERLAY);
        assertThat(out.detector().references()).singleElement()
                .satisfies(r -> {
                    assertThat(r.markerType()).isEqualTo("start");
                    assertThat(r.ocrText()).isEqualTo("GB AGENCY START");
                    assertThat(r.source()).isEqualTo("grid");
                    assertThat(r.shortcode()).isEqualTo("SC1");
                    assertThat(r.imageUrl()).isNull();
                });
        assertThat(out.weeklySchedule()).isSameAs(ws);
    }

    @Test
    void withDerivedDefinition_returnsUnchangedWhenNoWeeklySchedule() {
        VettedProfile in = new VettedProfile(base(), null, "blurb", null);
        assertThat(in.withDerivedDefinition()).isSameAs(in);
    }

    @Test
    void withDerivedDefinition_returnsUnchangedWhenEveryDayClosed() {
        WeeklyScheduleDefinition ws = new WeeklyScheduleDefinition(List.of(
                day(1, false, null, null, null), day(2, false, null, null, null)));
        VettedProfile in = new VettedProfile(base(), null, "blurb", ws);
        assertThat(in.withDerivedDefinition()).isSameAs(in);
    }

    @Test
    void toDetector_handlesNullReferencesAndFlattensSingleMarker() {
        DayDefinition d = new DayDefinition(1, true, SgType.SINGLE_MARKER, null, null, null, "12:00",
                null, null, null, null, 1, MarkerStyle.FLAT_BANNER, null);
        VettedProfile out = new VettedProfile(base(), null, "b", new WeeklyScheduleDefinition(List.of(d)))
                .withDerivedDefinition();
        assertThat(out.detector().references()).isEmpty();
        assertThat(out.detector().style()).isEqualTo(MarkerStyle.FLAT_BANNER);
        assertThat(out.definition().singleMarkerTime()).isEqualTo("12:00");
    }

    @Test
    void weeklySchedule_helpers_handleNullDays() {
        WeeklyScheduleDefinition ws = new WeeklyScheduleDefinition(null);
        assertThat(ws.openWeekdays()).isEmpty();
        assertThat(ws.representativeDay()).isEmpty();
    }
}
