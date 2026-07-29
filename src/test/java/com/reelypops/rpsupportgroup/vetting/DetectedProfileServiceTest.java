package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.group.DetectedProfile;
import com.reelypops.rpsupportgroup.group.DetectedProfile.MarkerReference;
import com.reelypops.rpsupportgroup.group.DetectedProfile.OwnerCandidate;
import com.reelypops.rpsupportgroup.group.DetectedProfile.RoundTime;
import com.reelypops.rpsupportgroup.group.DetectedProfile.ScheduleFacet;
import com.reelypops.rpsupportgroup.group.MarkerGroupType;
import com.reelypops.rpsupportgroup.group.MarkerStyle;
import com.reelypops.rpsupportgroup.group.RoundState;
import com.reelypops.rpsupportgroup.group.SupportGroupConfig;
import com.reelypops.rpsupportgroup.group.SupportGroupConfigRepository;
import com.reelypops.rpsupportgroup.vetting.DetectorProfileProposal.MarkerCluster;
import com.reelypops.rpsupportgroup.vetting.DetectorProfileProposal.ProposedType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the M3a advisory generator: it maps a Tier-0 {@link DetectorProfileProposal} into the facet-structured
 * {@link DetectedProfile} (style / owner / untyped references, each carrying confidence), persists it on the snapshot's
 * config, and 404s when that config is missing. All three marker styles are covered.
 */
class DetectedProfileServiceTest {

    private VettingProposalService proposals;
    private SupportGroupConfigRepository configs;
    private DetectedProfileService service;

    @BeforeEach
    void setUp() {
        proposals = mock(VettingProposalService.class);
        configs = mock(SupportGroupConfigRepository.class);
        service = new DetectedProfileService(proposals, configs);
    }

    private SnapshotAnalysis analysis(UUID snapshotId, ProposedType type, boolean escalate) {
        DetectorProfileProposal proposal = new DetectorProfileProposal(snapshotId, "glow.grp", 12, type,
                List.of("owner.acct"),
                List.of(new MarkerCluster("0000", 6, List.of("owner.acct"), List.of("sc-1", "sc-2"))),
                0.94, escalate, "TIER_0_DHASH");
        List<OwnerCandidate> candidates = List.of(new OwnerCandidate("owner.acct", 6, 6, 1.0, 0.9, 0.8, 3.8));
        List<MarkerReference> references = List.of(new MarkerReference("0000", 6, List.of("sc-1", "sc-2"), 0.95));
        ScheduleFacet schedule = new ScheduleFacet(MarkerGroupType.TWO_MARKER, 0.88,
                new RoundTime("09:00", 0.8), new RoundTime("17:00", 0.7), null,
                List.of(1, 2), 0.6, RoundState.OPEN, 1, 1.0, 0.9, 1.0, 4, 2, 0.75);
        return new SnapshotAnalysis(proposal, candidates, references, schedule, List.of(), List.of(), List.of());
    }

    @Test
    void detectMapsFlatBannerAnalysisAndPersistsOnConfig() {
        UUID snap = UUID.randomUUID();
        when(proposals.analyze(snap)).thenReturn(analysis(snap, ProposedType.FLAT_BANNER, false));
        SupportGroupConfig config = SupportGroupConfig.createRequested("glow.grp");
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile p = service.detect(snap);

        assertThat(p.snapshotId()).isEqualTo(snap);
        assertThat(p.igAccount()).isEqualTo("glow.grp");
        assertThat(p.itemCount()).isEqualTo(12);
        assertThat(p.provenance()).isEqualTo("TIER_0_DHASH");
        assertThat(p.escalate()).isFalse();
        assertThat(p.generatedAtMs()).isPositive();
        assertThat(p.imageStyle().value()).isEqualTo(MarkerStyle.FLAT_BANNER);
        assertThat(p.imageStyle().confidence()).isEqualTo(0.94);
        assertThat(p.owner().roster()).containsExactly("owner.acct");
        assertThat(p.owner().confidence()).isEqualTo(0.94);
        assertThat(p.references()).singleElement().satisfies(r -> {
            assertThat(r.dHash()).isEqualTo("0000");
            assertThat(r.distinctPosts()).isEqualTo(6);
            assertThat(r.sampleShortcodes()).containsExactly("sc-1", "sc-2");
            assertThat(r.confidence()).isEqualTo(0.95);
        });
        assertThat(p.candidates()).singleElement().satisfies(c -> {
            assertThat(c.author()).isEqualTo("owner.acct");
            assertThat(c.recurrence()).isEqualTo(6);
            assertThat(c.score()).isEqualTo(3.8);
        });
        assertThat(p.schedule().groupType()).isEqualTo(MarkerGroupType.TWO_MARKER);
        assertThat(p.schedule().start().timeOfDayUtc()).isEqualTo("09:00");
        assertThat(p.schedule().end().timeOfDayUtc()).isEqualTo("17:00");
        assertThat(p.schedule().openWeekdays()).containsExactly(1, 2);
        assertThat(p.schedule().currentState()).isEqualTo(RoundState.OPEN);
        assertThat(p.schedule().maxTaggedPosts()).isEqualTo(2);
        assertThat(p.schedule().maxTaggedPostsConfidence()).isEqualTo(0.75);
        assertThat(config.getDetectedProfile()).isSameAs(p);
        verify(configs).save(config);
    }

    /** A prior stored advisory carrying an explicit AI verdict (+ a one-pass run history) for {@code snapshotId}. */
    private DetectedProfile priorProfileWithAi(UUID snapshotId) {
        DetectedProfile.AiDiscovery ai = new DetectedProfile.AiDiscovery(MarkerStyle.TEXT_OVERLAY, "TWO_MARKER", "glow",
                List.of("glow"), List.of(), "START|ENDE", 0.8, "prior run", 5L, null, null,
                List.of(new DetectedProfile.AiDiscovery.AiPass("METRICS", 5L, "gpt-5", 100, 50, "0.0100", "USD", 3, false)));
        ScheduleFacet sched = new ScheduleFacet(MarkerGroupType.TWO_MARKER, 0.8, null, null, null,
                List.of(), 0.0, RoundState.UNKNOWN, null, 0.0, 0.0, 0.0, 0, null, 0.0);
        return new DetectedProfile(snapshotId, "glow.grp", 10, 1L, "TIER_0_DHASH", true,
                new DetectedProfile.StyleFacet(MarkerStyle.TEXT_OVERLAY, 0.5),
                new DetectedProfile.OwnerFacet(List.of("glow"), 0.5),
                List.of(), List.of(), sched, ai);
    }

    @Test
    void detectPreservesTheAiVerdictAndRunHistoryForTheSameSnapshot() {
        UUID snap = UUID.randomUUID();
        when(proposals.analyze(snap)).thenReturn(analysis(snap, ProposedType.FLAT_BANNER, false));
        SupportGroupConfig config = SupportGroupConfig.createRequested("glow.grp");
        config.updateDetectedProfile(priorProfileWithAi(snap)); // the operator ran AI earlier for THIS snapshot
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile p = service.detect(snap);

        // A plain Tier-0 re-detect (reopening the Advisory Dashboard) KEEPS the AI verdict + its run history.
        assertThat(p.aiDiscovery()).isNotNull();
        assertThat(p.aiDiscovery().owner()).isEqualTo("glow");
        assertThat(p.aiDiscovery().passes()).singleElement()
                .satisfies(pass -> assertThat(pass.kind()).isEqualTo("METRICS"));
        assertThat(p.imageStyle().value()).isEqualTo(MarkerStyle.FLAT_BANNER); // Tier-0 facets still regenerated
    }

    @Test
    void detectDropsAnAiVerdictStoredForADifferentSnapshot() {
        UUID snap = UUID.randomUUID();
        UUID otherSnap = UUID.randomUUID();
        when(proposals.analyze(snap)).thenReturn(analysis(snap, ProposedType.FLAT_BANNER, false));
        SupportGroupConfig config = SupportGroupConfig.createRequested("glow.grp");
        config.updateDetectedProfile(priorProfileWithAi(otherSnap)); // the verdict was for a DIFFERENT snapshot
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile p = service.detect(snap);

        assertThat(p.aiDiscovery()).isNull(); // its cited clusters / shortcodes don't apply to this snapshot
    }

    @Test
    void detectMapsTextOverlayStyle() {
        UUID snap = UUID.randomUUID();
        when(proposals.analyze(snap)).thenReturn(analysis(snap, ProposedType.TEXT_OVERLAY, true));
        SupportGroupConfig config = SupportGroupConfig.createRequested("glow.grp");
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile p = service.detect(snap);

        assertThat(p.imageStyle().value()).isEqualTo(MarkerStyle.TEXT_OVERLAY);
        assertThat(p.escalate()).isTrue();
    }

    @Test
    void detectMapsUnknownStyle() {
        UUID snap = UUID.randomUUID();
        when(proposals.analyze(snap)).thenReturn(analysis(snap, ProposedType.UNKNOWN, false));
        SupportGroupConfig config = SupportGroupConfig.createRequested("glow.grp");
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.of(config));

        DetectedProfile p = service.detect(snap);

        assertThat(p.imageStyle().value()).isEqualTo(MarkerStyle.UNKNOWN);
    }

    @Test
    void detectIsNotFoundWhenNoConfigForTheGroup() {
        UUID snap = UUID.randomUUID();
        when(proposals.analyze(snap)).thenReturn(analysis(snap, ProposedType.FLAT_BANNER, false));
        when(configs.findByIgAccount("glow.grp")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.detect(snap));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
