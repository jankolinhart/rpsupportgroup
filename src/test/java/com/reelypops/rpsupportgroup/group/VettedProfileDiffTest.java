package com.reelypops.rpsupportgroup.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reelypops.rpsupportgroup.group.VettedProfileVersion.ChangeNoteEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the structured field-level diff (M5 A2 / #5.3). Two {@link VettedProfile}s are built the way the
 * app does at runtime — deserialised from jsonb via Jackson — so the flattened paths match production exactly. The
 * diff is path + raw before/after values only (never composed prose), so the i18n client renders it.
 */
class VettedProfileDiffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BEFORE = """
            {"definition":{"type":"TWO_MARKER","timezone":"Europe/Berlin","markerOwners":["ras.circle"],
            "startMarkerTime":"08:00","endMarkerTime":"20:00","openWeekdays":[1,2,3,4,5]},
            "detector":{"style":"FLAT_BANNER","references":[{"markerType":"start","dHashes":["0000"],
            "ocrText":"START","matchThreshold":4}]},"description":"Old blurb."}""";

    // vs BEFORE: description + dHash + ocrText + threshold changed; a marker owner ADDED; two open days REMOVED.
    private static final String AFTER = """
            {"definition":{"type":"TWO_MARKER","timezone":"Europe/Berlin","markerOwners":["ras.circle","two.circle"],
            "startMarkerTime":"08:00","endMarkerTime":"20:00","openWeekdays":[1,2,3]},
            "detector":{"style":"FLAT_BANNER","references":[{"markerType":"start","dHashes":["1111"],
            "ocrText":"BEGIN","matchThreshold":6}]},"description":"New blurb."}""";

    private static VettedProfile profile(String json) throws Exception {
        return MAPPER.readValue(json, VettedProfile.class);
    }

    @Test
    void diffsChangedAddedAndRemovedFieldsAcrossObjectsAndArrays() throws Exception {
        List<ChangeNoteEntry> diff = VettedProfileDiff.diff(profile(BEFORE), profile(AFTER));

        assertThat(diff).contains(
                new ChangeNoteEntry("description", "Old blurb.", "New blurb."),                    // leaf changed
                new ChangeNoteEntry("detector.references[0].dHashes[0]", "0000", "1111"),           // array-leaf changed
                new ChangeNoteEntry("detector.references[0].ocrText", "START", "BEGIN"),            // nested leaf changed
                new ChangeNoteEntry("detector.references[0].matchThreshold", "4", "6"),             // nested number changed
                new ChangeNoteEntry("definition.markerOwners[1]", null, "two.circle"),              // added (from == null)
                new ChangeNoteEntry("definition.openWeekdays[3]", "4", null),                       // removed (to == null)
                new ChangeNoteEntry("definition.openWeekdays[4]", "5", null));                      // removed (to == null)

        // Unchanged fields never appear.
        assertThat(diff).extracting(ChangeNoteEntry::field)
                .doesNotContain("definition.type", "definition.timezone", "definition.markerOwners[0]");
    }

    @Test
    void identicalProfilesProduceNoChanges() throws Exception {
        assertThat(VettedProfileDiff.diff(profile(AFTER), profile(AFTER))).isEmpty();
    }

    @Test
    void bothNullProducesNoChanges() {
        assertThat(VettedProfileDiff.diff(null, null)).isEmpty();
    }

    @Test
    void fromNullIsAllAdds() throws Exception {
        List<ChangeNoteEntry> diff = VettedProfileDiff.diff(null, profile(AFTER));
        assertThat(diff).isNotEmpty().allSatisfy(e -> assertThat(e.from()).isNull());
    }
}
