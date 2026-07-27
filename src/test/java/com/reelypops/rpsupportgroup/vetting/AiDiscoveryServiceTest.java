package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient;
import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient.VettingRequest;
import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient.VettingResponse;
import com.reelypops.rpsupportgroup.corpus.CorpusRepresentative;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusService;
import com.reelypops.rpsupportgroup.group.DetectedProfile;
import com.reelypops.rpsupportgroup.group.DetectedProfile.MarkerReference;
import com.reelypops.rpsupportgroup.group.DetectedProfile.OwnerCandidate;
import com.reelypops.rpsupportgroup.group.MarkerStyle;
import com.reelypops.rpsupportgroup.group.SupportGroupConfig;
import com.reelypops.rpsupportgroup.group.SupportGroupConfigRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The explicit AI-discovery action: reduces Tier-0 clusters + representatives to a gateway request, maps the verdict
 * onto the {@link DetectedProfile.AiDiscovery} facet and persists it — and fails open (returns the Tier-0 advisory
 * unchanged) when the gateway is off/unavailable. Falls back to raw representative images for a text-overlay group.
 */
class AiDiscoveryServiceTest {

    private static final UUID SNAP = UUID.randomUUID();

    private final DetectedProfileService detected = mock(DetectedProfileService.class);
    private final VettingProposalService proposals = mock(VettingProposalService.class);
    private final MarkerCorpusService corpus = mock(MarkerCorpusService.class);
    private final RpAiGatewayClient gateway = mock(RpAiGatewayClient.class);
    private final SupportGroupConfigRepository configs = mock(SupportGroupConfigRepository.class);

    private AiDiscoveryService service(int maxImages) {
        return new AiDiscoveryService(detected, proposals, corpus, gateway, configs, maxImages);
    }

    private DetectedProfile base() {
        return new DetectedProfile(SNAP, "glow.grp", 966, 1L, "TIER_0_DHASH", true,
                new DetectedProfile.StyleFacet(MarkerStyle.TEXT_OVERLAY, 0.0),
                new DetectedProfile.OwnerFacet(List.of("glow"), 0.0),
                List.of(), List.of(), null, null);
    }

    private CorpusRepresentative rep() {
        // A real representative (not a mock) so it can be built inside Optional.of(...) without nested stubbing.
        return CorpusRepresentative.create(SNAP, "sc", new byte[]{1, 2, 3}, "image/jpeg"); // base64 = "AQID"
    }

    @Test
    void attachesTheVerdictWithClusterMetricsAndRepresentativeImages() {
        when(detected.detect(SNAP)).thenReturn(base());
        List<MarkerReference> refs = List.of(
                new MarkerReference("h0", 8, List.of("sc1"), 0.9),
                new MarkerReference("h1", 2, List.of("sc2"), 0.2)); // no aligned candidate ⇒ default metrics
        List<OwnerCandidate> candidates = List.of(new OwnerCandidate("glow", 8, 8, 1.0, 0.71, 0.86, 4.85));
        List<List<Instant>> refPostedAt = List.of(
                List.of(Instant.parse("2026-01-05T09:03:00Z"), Instant.parse("2026-01-06T09:05:00Z")), // Mon, Tue (UTC)
                List.of()); // second ref has no captured timings
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, candidates, refs, null, refPostedAt));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(corpus.getRepresentative(SNAP, "sc2")).thenReturn(Optional.empty()); // uncaptured ⇒ null image
        when(gateway.vet(any())).thenReturn(Optional.of(new VettingResponse("TEXT_OVERLAY", "TWO_MARKER", "glow",
                List.of(new VettingResponse.Reference("start", "Los geht's"),
                        new VettingResponse.Reference("end", "Das war's")),
                "Glow", 0.82, "two banners",
                List.of(
                        new VettingResponse.DaySchedule("MON", true, "TWO_MARKER", "TEXT_OVERLAY",
                                List.of(new VettingResponse.Reference("start", "Los geht's"),
                                        new VettingResponse.Reference("end", "Das war's")),
                                "09:00", "17:00", 0, 2, 0.9),
                        new VettingResponse.DaySchedule("SAT", false, null, null, null, null, null, null, null, null)))));
        SupportGroupConfig config = mock(SupportGroupConfig.class);
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile result = service(12).runAiDiscovery(SNAP);

        ArgumentCaptor<VettingRequest> request = ArgumentCaptor.forClass(VettingRequest.class);
        verify(gateway).vet(request.capture());
        VettingRequest sent = request.getValue();
        assertThat(sent.igAccount()).isEqualTo("glow.grp");
        assertThat(sent.itemCount()).isEqualTo(966);
        assertThat(sent.tier0Style()).isEqualTo("TEXT_OVERLAY");
        assertThat(sent.ownerRoster()).containsExactly("glow");
        assertThat(sent.timezone()).isEqualTo("UTC");
        assertThat(sent.clusters()).hasSize(2);
        assertThat(sent.clusters().get(0)).satisfies(c -> {
            assertThat(c.size()).isEqualTo(8);
            assertThat(c.authors()).containsExactly("glow");
            assertThat(c.recurrence()).isEqualTo(8);
            assertThat(c.score()).isEqualTo(4.85);
            assertThat(c.imageUrl()).isEqualTo("data:image/jpeg;base64,AQID");
            assertThat(c.occurrences()).extracting(VettingRequest.Occurrence::weekday).containsExactly("MON", "TUE");
            assertThat(c.occurrences()).extracting(VettingRequest.Occurrence::timeOfDayLocal)
                    .containsExactly("09:03", "09:05");
        });
        assertThat(sent.clusters().get(1)).satisfies(c -> {
            assertThat(c.authors()).isEmpty();
            assertThat(c.recurrence()).isZero();
            assertThat(c.imageUrl()).isNull();
            assertThat(c.occurrences()).isEmpty();
        });

        ArgumentCaptor<DetectedProfile> saved = ArgumentCaptor.forClass(DetectedProfile.class);
        verify(config).updateDetectedProfile(saved.capture());
        verify(configs).save(config);
        DetectedProfile.AiDiscovery ai = saved.getValue().aiDiscovery();
        assertThat(ai.style()).isEqualTo(MarkerStyle.TEXT_OVERLAY);
        assertThat(ai.markerType()).isEqualTo("TWO_MARKER");
        assertThat(ai.owner()).isEqualTo("glow");
        assertThat(ai.ocrTargetText()).isEqualTo("Glow");
        assertThat(ai.confidence()).isEqualTo(0.82);
        assertThat(ai.references()).extracting(DetectedProfile.AiDiscovery.AiReference::markerType)
                .containsExactly("start", "end");
        DetectedProfile.AiDiscovery.WeeklySchedule ws = ai.weeklySchedule();
        assertThat(ws.timezone()).isEqualTo("UTC");
        assertThat(ws.days()).hasSize(2);
        assertThat(ws.days().get(0)).satisfies(d -> {
            assertThat(d.weekday()).isEqualTo("MON");
            assertThat(d.open()).isTrue();
            assertThat(d.groupType()).isEqualTo("TWO_MARKER");
            assertThat(d.style()).isEqualTo("TEXT_OVERLAY");
            assertThat(d.markers()).extracting(DetectedProfile.AiDiscovery.AiReference::markerType)
                    .containsExactly("start", "end");
            assertThat(d.start()).isEqualTo("09:00");
            assertThat(d.end()).isEqualTo("17:00");
            assertThat(d.endMarkerDayOffset()).isEqualTo(0);
            assertThat(d.maxTaggedPosts()).isEqualTo(2);
            assertThat(d.confidence()).isEqualTo(0.9);
        });
        assertThat(ws.days().get(1)).satisfies(d -> {
            assertThat(d.weekday()).isEqualTo("SAT");
            assertThat(d.open()).isFalse();
            assertThat(d.markers()).isEmpty();
        });
        assertThat(result.aiDiscovery()).isEqualTo(ai);
    }

    @Test
    void failsOpenReturningTheBaseAdvisoryWhenTheGatewayIsOff() {
        DetectedProfile base = base();
        when(detected.detect(SNAP)).thenReturn(base);
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of()));
        when(corpus.detail(SNAP)).thenReturn(new MarkerCorpusService.SnapshotDetail(null, null, List.of()));
        when(gateway.vet(any())).thenReturn(Optional.empty());

        DetectedProfile result = service(12).runAiDiscovery(SNAP);

        assertThat(result).isSameAs(base);
        assertThat(result.aiDiscovery()).isNull();
        verify(configs, never()).findByIgAccount(any());
        verify(configs, never()).save(any());
    }

    @Test
    void fallsBackToRepresentativeImagesForATextOverlayGroup() {
        when(detected.detect(SNAP)).thenReturn(base());
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of()));
        when(corpus.detail(SNAP)).thenReturn(
                new MarkerCorpusService.SnapshotDetail(null, null, List.of("sc1", "sc2", "sc3")));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(corpus.getRepresentative(SNAP, "sc2")).thenReturn(Optional.empty()); // skipped
        when(corpus.getRepresentative(SNAP, "sc3")).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.of(
                new VettingResponse("FLAT_BANNER", "SINGLE_MARKER", "glow", null, null, 0.5, "flat", null)));
        SupportGroupConfig config = mock(SupportGroupConfig.class);
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile result = service(12).runAiDiscovery(SNAP);

        ArgumentCaptor<VettingRequest> request = ArgumentCaptor.forClass(VettingRequest.class);
        verify(gateway).vet(request.capture());
        assertThat(request.getValue().clusters()).hasSize(2); // sc1 + sc3, sc2 skipped
        assertThat(request.getValue().clusters()).allSatisfy(c -> assertThat(c.size()).isEqualTo(1));
        assertThat(result.aiDiscovery().style()).isEqualTo(MarkerStyle.FLAT_BANNER);
        assertThat(result.aiDiscovery().references()).isEmpty(); // null references map to an empty list
        assertThat(result.aiDiscovery().weeklySchedule()).isNull(); // null schedule maps to a null WeeklySchedule
    }

    @Test
    void mapsAnUnrecognisedStyleToUnknown() {
        when(detected.detect(SNAP)).thenReturn(base());
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null,
                List.of(new OwnerCandidate("glow", 4, 4, 1.0, 0.9, 0.8, 3.8)),
                List.of(new MarkerReference("h0", 4, List.of("sc1"), 0.9)), null, List.of()));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.of(
                new VettingResponse("WHAT", "CONTINUOUS", null, List.of(), null, 0.1, "?", List.of())));
        SupportGroupConfig config = mock(SupportGroupConfig.class);
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile result = service(12).runAiDiscovery(SNAP);

        assertThat(result.aiDiscovery().style()).isEqualTo(MarkerStyle.UNKNOWN);
        assertThat(result.aiDiscovery().owner()).isNull();
    }

    @Test
    void throws404WhenTheConfigIsMissing() {
        when(detected.detect(SNAP)).thenReturn(base());
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null,
                List.of(new OwnerCandidate("glow", 4, 4, 1.0, 0.9, 0.8, 3.8)),
                List.of(new MarkerReference("h0", 4, List.of("sc1"), 0.9)), null, List.of()));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.of(
                new VettingResponse("FLAT_BANNER", "SINGLE_MARKER", "glow", List.of(), null, 0.9, "ok", null)));
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(12).runAiDiscovery(SNAP))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no config");
    }

    @Test
    void capsClustersFromReferencesAtMaxImages() {
        when(detected.detect(SNAP)).thenReturn(base());
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null,
                List.of(new OwnerCandidate("glow", 8, 8, 1.0, 0.71, 0.86, 4.85),
                        new OwnerCandidate("glow", 6, 6, 1.0, 0.6, 0.7, 3.0)),
                List.of(new MarkerReference("h0", 8, List.of("sc1"), 0.9),
                        new MarkerReference("h1", 6, List.of("sc2"), 0.7)), null, List.of()));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.empty());

        service(1).runAiDiscovery(SNAP); // maxImages = 1

        ArgumentCaptor<VettingRequest> request = ArgumentCaptor.forClass(VettingRequest.class);
        verify(gateway).vet(request.capture());
        assertThat(request.getValue().clusters()).hasSize(1); // second reference dropped by the cap
    }

    @Test
    void capsRepresentativeFallbackAtMaxImages() {
        when(detected.detect(SNAP)).thenReturn(base());
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of()));
        when(corpus.detail(SNAP)).thenReturn(
                new MarkerCorpusService.SnapshotDetail(null, null, List.of("sc1", "sc2")));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.empty());

        service(1).runAiDiscovery(SNAP); // maxImages = 1

        ArgumentCaptor<VettingRequest> request = ArgumentCaptor.forClass(VettingRequest.class);
        verify(gateway).vet(request.capture());
        assertThat(request.getValue().clusters()).hasSize(1); // stops after the first captured representative
    }
}
