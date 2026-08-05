package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.corpus.CorpusRepresentative;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusService;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit coverage for vet-time display-image enrichment: corpus-backed refs get a locator; others are left unchanged. */
class MarkerImageEnricherTest {

    private final MarkerCorpusService corpus = mock(MarkerCorpusService.class);
    private final MarkerImageStore store = mock(MarkerImageStore.class);
    private final MarkerImageEnricher enricher = new MarkerImageEnricher(corpus, store);

    private static GroupDefinition definition() {
        return new GroupDefinition(SgType.TWO_MARKER, "Europe/Berlin", List.of("owner"), null, null,
                "00:00", "00:00", 0, null, null, null, null, null, null);
    }

    private static VettedProfile.TypedMarkerReference ref(String type, String shortcode) {
        return new VettedProfile.TypedMarkerReference(type, List.of("h"), null, 4, "grid", shortcode, null, null);
    }

    @Test
    void nullProfileIsNull() {
        assertThat(enricher.enrich("g", null)).isNull();
    }

    @Test
    void stampsLocatorOnCorpusBackedRefsInDetectorAndWeekly() {
        UUID snap = UUID.randomUUID();
        MarkerCorpusSnapshot snapshot = mock(MarkerCorpusSnapshot.class);
        when(snapshot.getId()).thenReturn(snap);
        when(corpus.list("g")).thenReturn(List.of(snapshot));
        CorpusRepresentative rep = mock(CorpusRepresentative.class);
        when(rep.getImage()).thenReturn(new byte[]{1});
        when(corpus.getRepresentative(snap, "SC1")).thenReturn(Optional.of(rep));
        when(corpus.getRepresentative(snap, "SC2")).thenReturn(Optional.empty()); // no representative → unchanged
        when(store.capture(any())).thenReturn(Optional.of("loc123"));

        VettedProfile.DetectorArtifacts detector = new VettedProfile.DetectorArtifacts(MarkerStyle.FLAT_BANNER,
                List.of(ref("start", "SC1"), ref("end", "SC2"), ref("single", null)));
        DayDefinition open = new DayDefinition(1, true, SgType.TWO_MARKER, "08:00", "20:00", 0, null, "23:00", 0,
                "10:00", 0, 2, MarkerStyle.FLAT_BANNER, List.of(ref("start", "SC1")));
        WeeklyScheduleDefinition weekly = new WeeklyScheduleDefinition(List.of(open));

        VettedProfile out = enricher.enrich("g", new VettedProfile(definition(), detector, "desc", weekly));

        assertThat(out.detector().references().get(0).imageLocator()).isEqualTo("loc123"); // SC1 resolved
        assertThat(out.detector().references().get(1).imageLocator()).isNull();            // SC2 no representative
        assertThat(out.detector().references().get(2).imageLocator()).isNull();            // null shortcode → skipped
        assertThat(out.weeklySchedule().days().get(0).references().get(0).imageLocator()).isEqualTo("loc123");
    }

    @Test
    void preservesNullDetectorAndNullWeeklyAndToleratesNoSnapshot() {
        when(corpus.list("g")).thenReturn(List.of()); // no snapshot → snapshotId resolves null
        VettedProfile out = enricher.enrich("g", new VettedProfile(definition(), null, "desc", null));
        assertThat(out.detector()).isNull();
        assertThat(out.weeklySchedule()).isNull();
    }
}
