package com.reelypops.rpsupportgroup.group;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Guards the "{@link VettedProfile} + {@link GroupDefinition} evolve without a migration" contract — both are stored as
 * jsonb on {@code SupportGroupConfig} ({@code vetted_profile} and the legacy {@code definition} projection). A profile
 * persisted under an OLDER schema (extra fields since removed/renamed) must still deserialize; without
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} on <em>every</em> record in the tree (the annotation does NOT
 * cascade), reading a stored row throws {@code UnrecognizedPropertyException} and the WHOLE support-group list 502s —
 * exactly the regression the M6 {@code OwnerCandidate} rename caused on the sibling {@link DetectedProfile}. M5
 * removes/renames fields in these two trees, so they must tolerate unknown props FIRST.
 */
class VettedProfileJsonCompatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesAnOldSchemaVettedProfileDroppingRemovedFieldsAtEveryLevel() throws Exception {
        // Unknown fields at EVERY level (top + definition + detector + reference + weeklySchedule + day) must be
        // dropped, not throw. openWeekdays is the day-of-week field M5 consumes — assert it survives intact.
        String oldJson = """
                {"legacyTopLevelFlag":true,
                 "definition":{"type":"TWO_MARKER","timezone":"Europe/Berlin",
                     "markerOwners":["ras.circle","lespechesmignonsbylia"],"maxTaggedPosts":2,
                     "continuousDays":[1,2,3],"startMarkerTime":"18:35","endMarkerTime":"18:35","endMarkerDayOffset":0,
                     "singleMarkerTime":null,"likesUntilTime":"12:00","likesUntilDayOffset":1,
                     "tagRemoveEarliestTime":"13:00","tagRemoveEarliestDayOffset":1,"openWeekdays":[1,2,3,4,5],
                     "graceMinutes":30},
                 "detector":{"style":"FLAT_BANNER","legacyDetectorVersion":2,
                     "references":[{"markerType":"start","dHashes":["aaaa","bbbb"],"matchThreshold":10,"confidence":0.9}]},
                 "description":"A test group",
                 "weeklySchedule":{"timezoneRemoved":"Europe/Berlin","days":[
                     {"weekday":1,"open":true,"type":"TWO_MARKER","startMarkerTime":"18:35","endMarkerTime":"18:35",
                      "endMarkerDayOffset":0,"singleMarkerTime":null,"likesUntilTime":"12:00","likesUntilDayOffset":1,
                      "tagRemoveEarliestTime":"13:00","tagRemoveEarliestDayOffset":1,"maxTaggedPosts":2,
                      "style":"FLAT_BANNER","note":"old-per-day-field",
                      "references":[{"markerType":"start","dHashes":["aaaa"],"matchThreshold":10,"legacyField":"x"}]}]}}
                """;

        VettedProfile vp = mapper.readValue(oldJson, VettedProfile.class);

        assertThat(vp.description()).isEqualTo("A test group");
        assertThat(vp.definition().type()).isEqualTo(SgType.TWO_MARKER);
        assertThat(vp.definition().markerOwners()).containsExactly("ras.circle", "lespechesmignonsbylia");
        assertThat(vp.definition().openWeekdays()).containsExactly(1, 2, 3, 4, 5); // the day-of-week field M5 reads
        assertThat(vp.detector().style()).isEqualTo(MarkerStyle.FLAT_BANNER);
        assertThat(vp.detector().references()).singleElement().satisfies(r -> {
            assertThat(r.markerType()).isEqualTo("start");
            assertThat(r.dHashes()).containsExactly("aaaa", "bbbb");
            assertThat(r.matchThreshold()).isEqualTo(10);
        });
        assertThat(vp.weeklySchedule().days()).singleElement().satisfies(d -> {
            assertThat(d.weekday()).isEqualTo(1);
            assertThat(d.open()).isTrue();
            assertThat(d.type()).isEqualTo(SgType.TWO_MARKER);
            assertThat(d.references()).singleElement().satisfies(r -> assertThat(r.markerType()).isEqualTo("start"));
        });
    }

    @Test
    void deserializesAnOldSchemaGroupDefinitionDroppingRemovedFields() throws Exception {
        // GroupDefinition is ALSO stored as its own jsonb column ("definition"), so a standalone old row must read too.
        String oldJson = """
                {"type":"CONTINUOUS","timezone":"Europe/Berlin","markerOwners":[],"maxTaggedPosts":null,
                 "continuousDays":[1,2,3,4,5],"openWeekdays":[1,2,3,4,5],"legacyRoundGraceMinutes":15,"purity":0.9}
                """;

        GroupDefinition def = mapper.readValue(oldJson, GroupDefinition.class);

        assertThat(def.type()).isEqualTo(SgType.CONTINUOUS);
        assertThat(def.openWeekdays()).containsExactly(1, 2, 3, 4, 5);
        assertThat(def.continuousDays()).containsExactly(1, 2, 3, 4, 5);
    }
}
