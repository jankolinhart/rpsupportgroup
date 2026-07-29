package com.reelypops.rpsupportgroup.group;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Guards the "{@link DetectedProfile} evolves without a migration" contract (the class is stored as jsonb on
 * {@code SupportGroupConfig#detectedProfile}). An advisory persisted under an OLDER schema — extra fields since removed —
 * must still deserialize. Without {@code @JsonIgnoreProperties(ignoreUnknown = true)} on the advisory records, reading a
 * stored row throws {@code UnrecognizedPropertyException} and the WHOLE support-group list 502s: exactly the regression
 * the M6 per-owner {@code OwnerCandidate} rename caused (old rows carried {@code score}/{@code recurrence}/… ).
 */
class DetectedProfileJsonCompatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesAnOldSchemaAdvisoryDroppingRemovedFields() throws Exception {
        // The pre-M6 shape: OwnerCandidate carried per-cluster metrics (distinctPosts/recurrence/purity/cadenceRegularity/
        // coverage/score) and MarkerReference had no dominantAuthor. All now gone/added — a stored row must still read.
        String oldJson = """
                {"snapshotId":"11111111-1111-1111-1111-111111111111","igAccount":"grp.one","itemCount":12,
                 "generatedAtMs":1234,"provenance":"TIER_0_DHASH","escalate":false,
                 "imageStyle":{"value":"FLAT_BANNER","confidence":0.9},
                 "owner":{"roster":["ras.circle"],"confidence":0.9},
                 "references":[{"dHash":"0000","distinctPosts":6,"sampleShortcodes":["sc1"],"confidence":0.9}],
                 "candidates":[{"author":"ras.circle","distinctPosts":6,"recurrence":6,"purity":1.0,
                                "cadenceRegularity":0.9,"coverage":0.8,"score":3.8}],
                 "schedule":{"groupType":"TWO_MARKER","groupTypeConfidence":0.88,"start":null,"end":null,"single":null,
                             "openWeekdays":[1,2],"openingDaysConfidence":0.6,"currentState":"OPEN",
                             "endMarkerDayOffset":null,"endMarkerDayOffsetConfidence":0.0,"symmetry":1.0,"pairing":0.9,
                             "roundCount":4,"maxTaggedPosts":2,"maxTaggedPostsConfidence":0.75},
                 "aiDiscovery":null}
                """;

        DetectedProfile p = mapper.readValue(oldJson, DetectedProfile.class);

        assertThat(p.igAccount()).isEqualTo("grp.one");
        // The removed candidate metrics are dropped; the surviving field (author) is kept, the new ones default.
        assertThat(p.candidates()).singleElement().satisfies(c -> {
            assertThat(c.author()).isEqualTo("ras.circle");
            assertThat(c.markerPosts()).isZero();
            assertThat(c.postShare()).isZero();
        });
        // A reference stored before dominantAuthor existed reads back with a null author (a new field, absent on old rows).
        assertThat(p.references()).singleElement().satisfies(r -> {
            assertThat(r.dHash()).isEqualTo("0000");
            assertThat(r.dominantAuthor()).isNull();
        });
        assertThat(p.schedule().groupType()).isEqualTo(MarkerGroupType.TWO_MARKER);
    }
}
