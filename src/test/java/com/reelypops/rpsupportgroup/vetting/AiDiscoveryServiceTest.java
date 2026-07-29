package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient;
import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient.VettingRequest;
import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient.VettingResponse;
import com.reelypops.rpsupportgroup.corpus.CorpusRepresentative;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusService;
import com.reelypops.rpsupportgroup.group.DetectedProfile;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        return new AiDiscoveryService(detected, proposals, corpus, gateway, configs, maxImages, 3);
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
        // The broadened AI sample (M4.6): the clean END banner (image sc1, Mon+Tue timings) plus a sub-threshold START
        // fragment (uncaptured image sc2, no timings) — both the proposed owner's, sent PAST the Tier-0 reference gate.
        List<AiMarkerSample> samples = List.of(
                new AiMarkerSample(8, "glow", 8, 0.71, 0.86, 4.85, List.of("sc1"),
                        List.of(Instant.parse("2026-01-05T09:03:00Z"), Instant.parse("2026-01-06T09:05:00Z"))), // Mon, Tue
                new AiMarkerSample(2, "glow", 2, 0.5, 0.2, 0.2, List.of("sc2"), List.of())); // uncaptured image, no timings
        // M4.7: the recent grid window in order — two owner markers bracketing a member post.
        List<GridRow> gridWindow = List.of(
                new GridRow(0, "glow", true), new GridRow(1, "member.a", false), new GridRow(2, "glow", true));
        when(proposals.analyze(SNAP)).thenReturn(
                new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(), samples, gridWindow));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(corpus.getRepresentative(SNAP, "sc2")).thenReturn(Optional.empty()); // uncaptured ⇒ null image
        when(gateway.vet(any())).thenReturn(Optional.of(new VettingResponse("TEXT_OVERLAY", "TWO_MARKER", "glow", List.of("glow", "co.owner"),
                List.of(new VettingResponse.Reference("start", "Los geht's", 2),
                        new VettingResponse.Reference("end", "Das war's", 1)),
                "Glow", 0.82, "two banners",
                List.of(
                        new VettingResponse.DaySchedule("MON", true, "TWO_MARKER", "TEXT_OVERLAY",
                                List.of(new VettingResponse.Reference("start", "Los geht's", 2),
                                        new VettingResponse.Reference("end", "Das war's", 1)),
                                "09:00", "17:00", 0, 2, 0.9),
                        new VettingResponse.DaySchedule("SAT", false, null, null, null, null, null, null, null, null)),
                null)));
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
            assertThat(c.size()).isEqualTo(2);
            assertThat(c.authors()).containsExactly("glow"); // every sample carries the proposed owner
            assertThat(c.recurrence()).isEqualTo(2);
            assertThat(c.imageUrl()).isNull(); // sc2 uncaptured
            assertThat(c.occurrences()).isEmpty();
        });
        // M4.7: the ordered grid window is relayed verbatim (markers flagged, in grid order).
        assertThat(sent.gridWindow()).hasSize(3);
        assertThat(sent.gridWindow()).extracting(VettingRequest.GridRow::ordinal).containsExactly(0, 1, 2);
        assertThat(sent.gridWindow()).extracting(VettingRequest.GridRow::marker).containsExactly(true, false, true);
        assertThat(sent.gridWindow().get(1).author()).isEqualTo("member.a");

        ArgumentCaptor<DetectedProfile> saved = ArgumentCaptor.forClass(DetectedProfile.class);
        verify(config).updateDetectedProfile(saved.capture());
        verify(configs).save(config);
        DetectedProfile.AiDiscovery ai = saved.getValue().aiDiscovery();
        assertThat(ai.style()).isEqualTo(MarkerStyle.TEXT_OVERLAY);
        assertThat(ai.markerType()).isEqualTo("TWO_MARKER");
        assertThat(ai.owner()).isEqualTo("glow");
        assertThat(ai.owners()).containsExactly("glow", "co.owner");
        assertThat(ai.ocrTargetText()).isEqualTo("Glow");
        assertThat(ai.confidence()).isEqualTo(0.82);
        assertThat(ai.references()).extracting(DetectedProfile.AiDiscovery.AiReference::markerType)
                .containsExactly("start", "end");
        // A2/B7b: each AI reference resolves to its cited cluster's representative shortcode (start cited cluster 2 = sc2,
        // end cited cluster 1 = sc1) so the admin can see every marker variation's image.
        assertThat(ai.references()).extracting(DetectedProfile.AiDiscovery.AiReference::shortcode)
                .containsExactly("sc2", "sc1");
        // Run history: the metrics pass is recorded (markersAdded = the 2-marker gallery it produced; not converged).
        assertThat(ai.passes()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo("METRICS");
            assertThat(p.markersAdded()).isEqualTo(2);
            assertThat(p.converged()).isFalse();
        });
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
            assertThat(d.markers()).extracting(DetectedProfile.AiDiscovery.AiReference::shortcode)
                    .containsExactly("sc2", "sc1");
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
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(), List.of(), List.of()));
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
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(), List.of(), List.of()));
        when(corpus.detail(SNAP)).thenReturn(
                new MarkerCorpusService.SnapshotDetail(null, null, List.of("sc1", "sc2", "sc3")));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(corpus.getRepresentative(SNAP, "sc2")).thenReturn(Optional.empty()); // skipped
        when(corpus.getRepresentative(SNAP, "sc3")).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.of(
                new VettingResponse("FLAT_BANNER", "SINGLE_MARKER", "glow", null, null, null, 0.5, "flat", null, null)));
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
        assertThat(result.aiDiscovery().owners()).containsExactly("glow"); // owners absent (older gateway) → fall back to [owner]
    }

    @Test
    void mapsAnUnrecognisedStyleToUnknown() {
        when(detected.detect(SNAP)).thenReturn(base());
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(),
                List.of(new AiMarkerSample(4, "glow", 4, 0.9, 0.8, 3.8, List.of("sc1"), List.of())), List.of()));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.of(
                new VettingResponse("WHAT", "CONTINUOUS", null, null,
                        List.of(new VettingResponse.Reference("single", "x", null)), null, 0.1, "?", List.of(), null)));
        SupportGroupConfig config = mock(SupportGroupConfig.class);
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile result = service(12).runAiDiscovery(SNAP);

        assertThat(result.aiDiscovery().style()).isEqualTo(MarkerStyle.UNKNOWN);
        assertThat(result.aiDiscovery().owner()).isNull();
        assertThat(result.aiDiscovery().owners()).isEmpty(); // no owner + no owners → empty set
        // A reference the AI did not tie to a cluster (null clusterIndex) resolves to a null shortcode.
        assertThat(result.aiDiscovery().references()).singleElement()
                .satisfies(r -> assertThat(r.shortcode()).isNull());
    }

    @Test
    void throws404WhenTheConfigIsMissing() {
        when(detected.detect(SNAP)).thenReturn(base());
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(),
                List.of(new AiMarkerSample(4, "glow", 4, 0.9, 0.8, 3.8, List.of("sc1"), List.of())), List.of()));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.of(
                new VettingResponse("FLAT_BANNER", "SINGLE_MARKER", "glow", List.of("glow"), List.of(), null, 0.9, "ok", null, null)));
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(12).runAiDiscovery(SNAP))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no config");
    }

    @Test
    void capsClustersFromSamplesAtMaxImages() {
        when(detected.detect(SNAP)).thenReturn(base());
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(),
                List.of(new AiMarkerSample(8, "glow", 8, 0.71, 0.86, 4.85, List.of("sc1"), List.of()),
                        new AiMarkerSample(6, "glow", 6, 0.6, 0.7, 3.0, List.of("sc2"), List.of())), List.of()));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.empty());

        service(1).runAiDiscovery(SNAP); // maxImages = 1

        ArgumentCaptor<VettingRequest> request = ArgumentCaptor.forClass(VettingRequest.class);
        verify(gateway).vet(request.capture());
        assertThat(request.getValue().clusters()).hasSize(1); // second sample dropped by the cap
    }

    @Test
    void capsRepresentativeFallbackAtMaxImages() {
        when(detected.detect(SNAP)).thenReturn(base());
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(), List.of(), List.of()));
        when(corpus.detail(SNAP)).thenReturn(
                new MarkerCorpusService.SnapshotDetail(null, null, List.of("sc1", "sc2")));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.empty());

        service(1).runAiDiscovery(SNAP); // maxImages = 1

        ArgumentCaptor<VettingRequest> request = ArgumentCaptor.forClass(VettingRequest.class);
        verify(gateway).vet(request.capture());
        assertThat(request.getValue().clusters()).hasSize(1); // stops after the first captured representative
    }

    @Test
    void sendsUpToPerClusterSamplesCapturedImagesPerCluster() {
        when(detected.detect(SNAP)).thenReturn(base());
        // One owner cluster whose sample list has FOUR captured posts (a plain marker + weekday variants the dHash pass
        // absorbed into it). We send at most perClusterSamples (3) images of the cluster so the absorbed variants surface.
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(),
                List.of(new AiMarkerSample(8, "glow", 8, 0.71, 0.86, 4.85, List.of("a", "b", "c", "d"), List.of())), List.of()));
        when(corpus.getRepresentative(eq(SNAP), anyString())).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.empty());

        service(12).runAiDiscovery(SNAP);

        ArgumentCaptor<VettingRequest> request = ArgumentCaptor.forClass(VettingRequest.class);
        verify(gateway).vet(request.capture());
        assertThat(request.getValue().clusters()).hasSize(3); // 4 captured samples, capped at perClusterSamples = 3
        assertThat(request.getValue().clusters()).allSatisfy(c -> assertThat(c.imageUrl()).isNotNull());
    }

    @Test
    void sendsOneImagePerClusterBeforeDeepeningSoEveryVariantIsRepresented() {
        when(detected.detect(SNAP)).thenReturn(base());
        // Two owner clusters, each with TWO captured images. With maxImages = 3 the round-robin must give BOTH clusters a
        // representative image (depth 0: one of each) BEFORE spending the third slot deepening the first — so a
        // low-recurrence variant cluster is never starved by another cluster's extra samples under the image cap.
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(),
                List.of(new AiMarkerSample(8, "glow", 8, 0.7, 0.8, 4.0, List.of("a1", "a2"), List.of()),
                        new AiMarkerSample(2, "glow", 2, 0.5, 0.2, 0.2, List.of("b1", "b2"), List.of())), List.of()));
        when(corpus.getRepresentative(eq(SNAP), anyString())).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.empty());

        service(3).runAiDiscovery(SNAP); // maxImages = 3

        ArgumentCaptor<VettingRequest> request = ArgumentCaptor.forClass(VettingRequest.class);
        verify(gateway).vet(request.capture());
        List<VettingRequest.Cluster> sent = request.getValue().clusters();
        assertThat(sent).hasSize(3);
        // depth 0 sends one image of EACH cluster first (the size-8 marker, then the size-2 variant), then the third slot
        // deepens the first cluster — the size-2 variant is represented, not starved by the size-8 cluster's extra sample.
        assertThat(sent).extracting(VettingRequest.Cluster::size).containsExactly(8, 2, 8);
    }

    @Test
    void readGroundsTheGalleryToImageBearingClustersOverridingTheCompoundVerdict() {
        when(detected.detect(SNAP)).thenReturn(base());
        // sample 0 = captured sc1, sample 1 = UNCAPTURED sc2 (image-less), sample 2 = captured sc3.
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(),
                List.of(new AiMarkerSample(8, "glow", 8, 0.7, 0.8, 4.0, List.of("sc1"), List.of()),
                        new AiMarkerSample(2, "glow", 2, 0.5, 0.2, 0.2, List.of("sc2"), List.of()),
                        new AiMarkerSample(3, "glow", 3, 0.6, 0.5, 1.5, List.of("sc3"), List.of())), List.of()));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(corpus.getRepresentative(SNAP, "sc2")).thenReturn(Optional.empty()); // image-less → skipped from the read
        when(corpus.getRepresentative(SNAP, "sc3")).thenReturn(Optional.of(rep()));
        // vet (compound) supplies style / type / owner / schedule + usage; its OWN reference is the mis-grounded bug we drop.
        when(gateway.vet(any())).thenReturn(Optional.of(new VettingResponse("TEXT_OVERLAY", "TWO_MARKER", "glow", List.of("glow"),
                List.of(new VettingResponse.Reference("start", "GB AGENCY START Sonntag", 1)),
                "START|ENDE", 0.8, "compound", List.of(),
                new RpAiGatewayClient.Usage("gpt-5", 200, 50, "0.0100", "USD"))));
        // read (per-image OCR) supplies the GROUNDED gallery: image 1 = plain START, image 2 = ENDE Sonntag.
        // markerReads null here (defensive) — this test asserts the gallery (from markers); weekday grounding is tested below.
        when(gateway.read(any())).thenReturn(Optional.of(new RpAiGatewayClient.ReadResponse(
                List.of(new VettingResponse.Reference("start", "GB AGENCY START", 1),
                        new VettingResponse.Reference("end", "GB AGENCY ENDE Sonntag", 2)),
                null, new RpAiGatewayClient.Usage("gpt-5", 40, 10, "0.0025", "USD"))));
        SupportGroupConfig config = mock(SupportGroupConfig.class);
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile result = service(12).runAiDiscovery(SNAP);

        // The read pass is sent ONLY image-bearing clusters (sc1, sc3) — the image-less sc2 is skipped.
        ArgumentCaptor<RpAiGatewayClient.ReadRequest> readReq =
                ArgumentCaptor.forClass(RpAiGatewayClient.ReadRequest.class);
        verify(gateway).read(readReq.capture());
        assertThat(readReq.getValue().clusters()).extracting(VettingRequest.Cluster::imageUrl)
                .containsExactly("data:image/jpeg;base64,AQID", "data:image/jpeg;base64,AQID"); // sc1, sc3 (sc2 dropped)

        DetectedProfile.AiDiscovery ai = result.aiDiscovery();
        // Gallery = the grounded per-image reads (NOT the compound verdict's mis-grounded reference).
        assertThat(ai.references()).extracting(DetectedProfile.AiDiscovery.AiReference::ocrText)
                .containsExactly("GB AGENCY START", "GB AGENCY ENDE Sonntag");
        // Alignment: read image 1 → sc1, image 2 → sc3 (the image-less sc2 did NOT shift the mapping).
        assertThat(ai.references()).extracting(DetectedProfile.AiDiscovery.AiReference::shortcode)
                .containsExactly("sc1", "sc3");
        // Style / type / owner / OCR-target still come from the compound vet verdict.
        assertThat(ai.style()).isEqualTo(MarkerStyle.TEXT_OVERLAY);
        assertThat(ai.markerType()).isEqualTo("TWO_MARKER");
        assertThat(ai.ocrTargetText()).isEqualTo("START|ENDE");
        // Metrics-pass cost = vet + read summed: tokens 200+40 / 50+10, cost 0.0100 + 0.0025 = 0.0125.
        assertThat(ai.usage().promptTokens()).isEqualTo(240);
        assertThat(ai.usage().completionTokens()).isEqualTo(60);
        assertThat(ai.usage().costEstimate()).isEqualTo("0.0125");
        assertThat(ai.usage().model()).isEqualTo("gpt-5");
        // The metrics pass in the run history carries the SAME combined spend + the 2-marker gallery it produced.
        assertThat(ai.passes()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo("METRICS");
            assertThat(p.markersAdded()).isEqualTo(2);
            assertThat(p.promptTokens()).isEqualTo(240);
            assertThat(p.costEstimate()).isEqualTo("0.0125");
            assertThat(p.model()).isEqualTo("gpt-5");
            assertThat(p.converged()).isFalse();
        });
    }

    @Test
    void usesTheCompoundGalleryAndVetUsageWhenTheReadPassIsDown() {
        when(detected.detect(SNAP)).thenReturn(base());
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(),
                List.of(new AiMarkerSample(8, "glow", 8, 0.7, 0.8, 4.0, List.of("sc1"), List.of())), List.of()));
        when(corpus.getRepresentative(SNAP, "sc1")).thenReturn(Optional.of(rep()));
        when(gateway.vet(any())).thenReturn(Optional.of(new VettingResponse("TEXT_OVERLAY", "TWO_MARKER", "glow", List.of("glow"),
                List.of(new VettingResponse.Reference("start", "GB AGENCY START", 1)), "START|ENDE", 0.8, "compound",
                List.of(), new RpAiGatewayClient.Usage("gpt-5", 200, 50, "0.0100", "USD"))));
        when(gateway.read(any())).thenReturn(Optional.empty()); // read pass down → keep the compound gallery + vet usage
        SupportGroupConfig config = mock(SupportGroupConfig.class);
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile.AiDiscovery ai = service(12).runAiDiscovery(SNAP).aiDiscovery();

        // Read down → gallery falls back to the compound verdict's reference (grounded via the N-space shortcodes).
        assertThat(ai.references()).extracting(DetectedProfile.AiDiscovery.AiReference::ocrText)
                .containsExactly("GB AGENCY START");
        assertThat(ai.references().get(0).shortcode()).isEqualTo("sc1");
        // Usage = vet's only (read contributed nothing).
        assertThat(ai.usage().promptTokens()).isEqualTo(200);
        assertThat(ai.usage().costEstimate()).isEqualTo("0.0100");
    }

    @Test
    void groundsPerWeekdayMarkersFromReadOccurrencesWithRecurrenceConfidence() {
        when(detected.detect(SNAP)).thenReturn(base());
        // START posted on two Mondays (06:00, 06:05) + one Tuesday (07:00); ENDE one Monday evening (18:30). START has
        // TWO captured images (sc1, sc1b), both read as START, so its occurrences are seen twice — the repeat is deduped.
        Instant monA = Instant.parse("2026-01-05T06:00:00Z");
        Instant monB = Instant.parse("2026-01-12T06:05:00Z");
        Instant tueA = Instant.parse("2026-01-06T07:00:00Z");
        Instant monEnd = Instant.parse("2026-01-05T18:30:00Z");
        when(proposals.analyze(SNAP)).thenReturn(new SnapshotAnalysis(null, List.of(), List.of(), null, List.of(),
                List.of(new AiMarkerSample(3, "glow", 3, 0.9, 0.9, 4.0, List.of("sc1", "sc1b"), List.of(monA, monB, tueA)),
                        new AiMarkerSample(1, "glow", 1, 0.5, 0.5, 1.0, List.of("sc2"), List.of(monEnd))), List.of()));
        when(corpus.getRepresentative(eq(SNAP), anyString())).thenReturn(Optional.of(rep()));
        // Compound schedule reports MONDAY only (open); its per-day markers get REPLACED by the grounded ones.
        when(gateway.vet(any())).thenReturn(Optional.of(new VettingResponse("TEXT_OVERLAY", "TWO_MARKER", "glow", List.of("glow"),
                List.of(), "START|ENDE", 0.8, "x",
                List.of(new VettingResponse.DaySchedule("MON", true, "TWO_MARKER", "TEXT_OVERLAY", List.of(),
                        "06:00", "18:30", 0, 1, 0.9)),
                null)));
        // Build order: 1=START(sc1), 2=ENDE(sc2), 3=START(sc1b). markerReads also carries a null-text + an out-of-range
        // entry (both skipped) and the 2nd START image (occurrences deduped).
        when(gateway.read(any())).thenReturn(Optional.of(new RpAiGatewayClient.ReadResponse(
                List.of(new VettingResponse.Reference("start", "GB AGENCY START", 1),
                        new VettingResponse.Reference("end", "ENDE", 2)),
                List.of(new VettingResponse.Reference("start", "GB AGENCY START", 1),
                        new VettingResponse.Reference("end", "ENDE", 2),
                        new VettingResponse.Reference("start", "GB AGENCY START", 3),  // 2nd START image → occurrences deduped
                        new VettingResponse.Reference("single", null, 1),              // null text → skipped
                        new VettingResponse.Reference("end", "X", 99)),               // clusterIndex out of range → skipped
                new RpAiGatewayClient.Usage("gpt-5", 40, 10, "0.0025", "USD"))));
        SupportGroupConfig config = mock(SupportGroupConfig.class);
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile.AiDiscovery.WeeklySchedule ws = service(12).runAiDiscovery(SNAP).aiDiscovery().weeklySchedule();

        // MON kept from the compound schedule (markers REPLACED by grounded) + TUE added (a grounded weekday the compound
        // pass didn't report), sorted MON→TUE.
        assertThat(ws.days()).extracting(DetectedProfile.AiDiscovery.WeeklySchedule.DaySchedule::weekday)
                .containsExactly("MON", "TUE");
        DetectedProfile.AiDiscovery.WeeklySchedule.DaySchedule mon = ws.days().get(0);
        assertThat(mon.open()).isTrue();
        assertThat(mon.start()).isEqualTo("06:00");        // compound day fields preserved
        assertThat(mon.maxTaggedPosts()).isEqualTo(1);
        assertThat(mon.markers()).extracting(DetectedProfile.AiDiscovery.AiReference::ocrText)
                .containsExactlyInAnyOrder("GB AGENCY START", "ENDE");
        assertThat(mon.markers()).filteredOn(m -> "GB AGENCY START".equals(m.ocrText())).singleElement()
                .satisfies(m -> {
                    assertThat(m.markerType()).isEqualTo("start");
                    assertThat(m.shortcode()).isEqualTo("sc1");    // first cited image of START
                    assertThat(m.confidence()).isEqualTo(0.75);    // 2 distinct Monday sightings → 1 - 0.5^2 (dup image ignored)
                });
        assertThat(mon.markers()).filteredOn(m -> "ENDE".equals(m.ocrText())).singleElement()
                .satisfies(m -> assertThat(m.confidence()).isEqualTo(0.5)); // 1 Monday sighting → 1 - 0.5^1
        DetectedProfile.AiDiscovery.WeeklySchedule.DaySchedule tue = ws.days().get(1);
        assertThat(tue.open()).isTrue();
        assertThat(tue.start()).isNull();                  // added day carries only the grounded markers
        assertThat(tue.markers()).extracting(DetectedProfile.AiDiscovery.AiReference::ocrText)
                .containsExactly("GB AGENCY START");
        assertThat(tue.markers()).singleElement().satisfies(m -> assertThat(m.confidence()).isEqualTo(0.5)); // 1 Tuesday
    }

    // ── Convergent refinement pass ──────────────────────────────────────────────────────────────────────────────────

    private DetectedProfile storedWithAi(List<DetectedProfile.AiDiscovery.AiReference> refs) {
        DetectedProfile b = base();
        DetectedProfile.AiDiscovery.AiPass metrics = new DetectedProfile.AiDiscovery.AiPass(
                "METRICS", 1L, "gpt-5", 100, 50, "0.0100", "USD", refs.size(), false);
        DetectedProfile.AiDiscovery ai = new DetectedProfile.AiDiscovery(MarkerStyle.TEXT_OVERLAY, "TWO_MARKER", "glow",
                List.of("glow", "co.owner"), refs, "START|ENDE", 0.7, "metrics pass", 1L, null, null, List.of(metrics));
        return new DetectedProfile(b.snapshotId(), b.igAccount(), b.itemCount(), b.generatedAtMs(), b.provenance(),
                b.escalate(), b.imageStyle(), b.owner(), b.references(), b.candidates(), b.schedule(), ai);
    }

    private SnapshotAnalysis analysisWith(List<AiMarkerSample> samples) {
        return new SnapshotAnalysis(
                new DetectorProfileProposal(SNAP, "glow.grp", 966, DetectorProfileProposal.ProposedType.TEXT_OVERLAY,
                        List.of("glow"), List.of(), 0.5, true, "TIER_0_DHASH"),
                List.of(), List.of(), null, List.of(), samples, List.of());
    }

    @Test
    void refineMergesGroundedNewMarkersDedupingAndReportsAddedCount() {
        // Stored metrics-pass advisory: two markers already found. The refine reply carries one genuine new template
        // (kept, grounded to image 1 → sc3) plus an already-found one, a null-text one, a blank one, and a within-pass
        // duplicate — all dropped. So added = 1 and the new marker resolves to its cited cluster's shortcode.
        DetectedProfile stored = storedWithAi(List.of(
                new DetectedProfile.AiDiscovery.AiReference("start", "GB AGENCY START", "sc1", null),
                new DetectedProfile.AiDiscovery.AiReference("end", "ENDE", "sc2", null)));
        when(proposals.analyze(SNAP)).thenReturn(
                analysisWith(List.of(new AiMarkerSample(2, "glow", 2, 0.5, 0.5, 1.0, List.of("sc3"), List.of()))));
        SupportGroupConfig config = mock(SupportGroupConfig.class);
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));
        when(config.getDetectedProfile()).thenReturn(stored);
        when(corpus.getRepresentative(SNAP, "sc3")).thenReturn(Optional.of(rep()));
        when(gateway.read(any())).thenReturn(Optional.of(new RpAiGatewayClient.ReadResponse(
                List.of(new VettingResponse.Reference("end", "GB AGENCY ENDE Samstag", 1),
                        new VettingResponse.Reference("start", "GB AGENCY START", 1),      // already found → dropped
                        new VettingResponse.Reference("single", null, 1),                  // null text → dropped
                        new VettingResponse.Reference("end", "   ", 1),                    // blank → dropped
                        new VettingResponse.Reference("end", "gb agency  ende samstag", 1)), // within-pass dup → dropped
                List.of(), new RpAiGatewayClient.Usage("gpt-5", 100, 30, "0.0021", "USD"))));

        AiRefineResult out = service(12).refineAiDiscovery(SNAP);

        assertThat(out.added()).isEqualTo(1);
        assertThat(out.profile().aiDiscovery().references())
                .extracting(DetectedProfile.AiDiscovery.AiReference::ocrText)
                .containsExactly("GB AGENCY START", "ENDE", "GB AGENCY ENDE Samstag");
        assertThat(out.profile().aiDiscovery().references().get(2).shortcode()).isEqualTo("sc3"); // clusterIndex 1 → sc3
        assertThat(out.profile().aiDiscovery().usage().model()).isEqualTo("gpt-5");
        assertThat(out.profile().aiDiscovery().usage().costEstimate()).isEqualTo("0.0021");
        assertThat(out.profile().aiDiscovery().owners()).containsExactly("glow", "co.owner"); // owner SET preserved across refine
        // Run history: the stored metrics pass + an appended REFINE pass (markersAdded = 1, not converged).
        assertThat(out.profile().aiDiscovery().passes()).hasSize(2);
        assertThat(out.profile().aiDiscovery().passes().get(0).kind()).isEqualTo("METRICS");
        DetectedProfile.AiDiscovery.AiPass refinePass = out.profile().aiDiscovery().passes().get(1);
        assertThat(refinePass.kind()).isEqualTo("REFINE");
        assertThat(refinePass.markersAdded()).isEqualTo(1);
        assertThat(refinePass.converged()).isFalse();
        assertThat(refinePass.model()).isEqualTo("gpt-5");
        assertThat(refinePass.costEstimate()).isEqualTo("0.0021");
        verify(config).updateDetectedProfile(any());
        verify(configs).save(config);
    }

    @Test
    void refineRecordsAConvergedPassWhenItAddsNoNewMarkers() {
        // The read re-reports ONLY markers already in the gallery → nothing new → the appended pass is CONVERGED.
        DetectedProfile stored = storedWithAi(List.of(
                new DetectedProfile.AiDiscovery.AiReference("start", "GB AGENCY START", "sc1", null),
                new DetectedProfile.AiDiscovery.AiReference("end", "ENDE", "sc2", null)));
        when(proposals.analyze(SNAP)).thenReturn(
                analysisWith(List.of(new AiMarkerSample(2, "glow", 2, 0.5, 0.5, 1.0, List.of("sc3"), List.of()))));
        SupportGroupConfig config = mock(SupportGroupConfig.class);
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));
        when(config.getDetectedProfile()).thenReturn(stored);
        when(corpus.getRepresentative(SNAP, "sc3")).thenReturn(Optional.of(rep()));
        when(gateway.read(any())).thenReturn(Optional.of(new RpAiGatewayClient.ReadResponse(
                List.of(new VettingResponse.Reference("start", "GB AGENCY START", 1),
                        new VettingResponse.Reference("end", "ENDE", 1)),
                List.of(), new RpAiGatewayClient.Usage("gpt-5", 40, 10, "0.0005", "USD"))));

        AiRefineResult out = service(12).refineAiDiscovery(SNAP);

        assertThat(out.added()).isZero();
        assertThat(out.profile().aiDiscovery().passes()).hasSize(2);
        DetectedProfile.AiDiscovery.AiPass converged = out.profile().aiDiscovery().passes().get(1);
        assertThat(converged.kind()).isEqualTo("REFINE");
        assertThat(converged.markersAdded()).isZero();
        assertThat(converged.converged()).isTrue();
    }

    @Test
    void refineIsANoOpWhenNoMetricsPassAdvisoryExists() {
        DetectedProfile stored = base(); // aiDiscovery == null
        when(proposals.analyze(SNAP)).thenReturn(analysisWith(List.of()));
        SupportGroupConfig config = mock(SupportGroupConfig.class);
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));
        when(config.getDetectedProfile()).thenReturn(stored);

        AiRefineResult out = service(12).refineAiDiscovery(SNAP);

        assertThat(out.added()).isZero();
        assertThat(out.profile()).isSameAs(stored);
        verify(gateway, never()).read(any());
        verify(config, never()).updateDetectedProfile(any());
    }

    @Test
    void refineIsANoOpWhenTheGatewayIsOffOrFails() {
        DetectedProfile stored = storedWithAi(List.of(
                new DetectedProfile.AiDiscovery.AiReference("start", "GB AGENCY START", "sc1", null)));
        when(proposals.analyze(SNAP)).thenReturn(
                analysisWith(List.of(new AiMarkerSample(1, "glow", 1, 0.0, 0.0, 0.0, List.of("sc3"), List.of()))));
        SupportGroupConfig config = mock(SupportGroupConfig.class);
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));
        when(config.getDetectedProfile()).thenReturn(stored);
        when(corpus.getRepresentative(SNAP, "sc3")).thenReturn(Optional.of(rep()));
        when(gateway.read(any())).thenReturn(Optional.empty()); // disabled / transport failure → converged

        AiRefineResult out = service(12).refineAiDiscovery(SNAP);

        assertThat(out.added()).isZero();
        assertThat(out.profile()).isSameAs(stored);
        verify(config, never()).updateDetectedProfile(any());
    }

    @Test
    void refineThrows404WhenTheConfigIsMissing() {
        when(proposals.analyze(SNAP)).thenReturn(analysisWith(List.of()));
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(12).refineAiDiscovery(SNAP))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no config");
    }
}
