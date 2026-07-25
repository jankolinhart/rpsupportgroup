package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.corpus.CorpusSnapshotItem;
import com.reelypops.rpsupportgroup.corpus.CorpusSnapshotItemRepository;
import com.reelypops.rpsupportgroup.corpus.CorpusSource;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusSnapshot;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusSnapshotRepository;
import com.reelypops.rpsupportgroup.vetting.DetectorProfileProposal.ProposedType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the Tier-0 (dHash-cluster) proposal engine: a recurring banner is proposed as FLAT_BANNER with an owner
 * roster + confidence; a grid where nothing recurs escalates as TEXT_OVERLAY; empty is UNKNOWN; the length guard keeps
 * differently-sized hashes apart; an unknown snapshot is a 404.
 */
class VettingProposalServiceTest {

    private static final String H0 = "00000000";
    private static final String H0_NEAR = "00000001"; // Hamming 1 from H0
    private static final String H_FAR = "11111111";   // Hamming 8 from H0
    private static final String H_FAR2 = "00001111";  // Hamming 4 from H0, 4 from H_FAR

    private MarkerCorpusSnapshotRepository snapshots;
    private CorpusSnapshotItemRepository items;
    private VettingProposalService service;
    private final AtomicInteger ordinal = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        snapshots = mock(MarkerCorpusSnapshotRepository.class);
        items = mock(CorpusSnapshotItemRepository.class);
        // threshold 2, min-cluster 2, max-clusters 5, max-samples 5
        service = new VettingProposalService(snapshots, items, new NoOpAiVettingEnricher(), 2, 2, 5, 5);
    }

    private MarkerCorpusSnapshot stubSnapshot(List<CorpusSnapshotItem> gridItems) {
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        when(snapshots.findById(snap.getId())).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(snap.getId())).thenReturn(gridItems);
        return snap;
    }

    private CorpusSnapshotItem item(UUID snapshotId, String author, String dHash) {
        int o = ordinal.getAndIncrement();
        return CorpusSnapshotItem.of(snapshotId, "sc-" + o, author, dHash, null, o);
    }

    @Test
    void unknownSnapshotIs404() {
        UUID missing = UUID.randomUUID();
        when(snapshots.findById(missing)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.propose(missing));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void emptySnapshotIsUnknown() {
        MarkerCorpusSnapshot snap = stubSnapshot(List.of());

        DetectorProfileProposal p = service.propose(snap.getId());

        assertThat(p.proposedType()).isEqualTo(ProposedType.UNKNOWN);
        assertThat(p.itemCount()).isZero();
        assertThat(p.confidence()).isZero();
        assertThat(p.escalate()).isFalse();
        assertThat(p.ownerRoster()).isEmpty();
        assertThat(p.markerClusters()).isEmpty();
        assertThat(p.snapshotId()).isEqualTo(snap.getId());
        assertThat(p.igAccount()).isEqualTo("glow.grp");
        assertThat(p.provenance()).isEqualTo("TIER_0_DHASH");
    }

    @Test
    void recurringBannerIsProposedAsFlatBanner() {
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                item(id, "owner.acct", H0),
                item(id, "owner.acct", H0_NEAR), // clusters with H0 (Hamming 1 <= 2)
                item(id, "owner.acct", H0),
                item(id, "member.a", H_FAR),
                item(id, "member.b", H_FAR2));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        DetectorProfileProposal p = service.propose(id);

        assertThat(p.proposedType()).isEqualTo(ProposedType.FLAT_BANNER);
        assertThat(p.itemCount()).isEqualTo(5);
        assertThat(p.ownerRoster()).containsExactly("owner.acct");
        assertThat(p.escalate()).isFalse();
        assertThat(p.confidence()).isEqualTo(3.0 / 5.0);
        assertThat(p.markerClusters()).hasSize(1);
        DetectorProfileProposal.MarkerCluster top = p.markerClusters().get(0);
        assertThat(top.size()).isEqualTo(3);
        assertThat(top.dHash()).isEqualTo(H0);
        assertThat(top.authorUsernames()).containsExactly("owner.acct");
        assertThat(top.sampleShortcodes()).hasSize(3);
    }

    @Test
    void gridWhereNothingRecursEscalatesAsTextOverlay() {
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                item(id, "a.acct", H0),
                item(id, "b.acct", H_FAR),
                item(id, "c.acct", H_FAR2)); // all pairwise Hamming > 2
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        DetectorProfileProposal p = service.propose(id);

        assertThat(p.proposedType()).isEqualTo(ProposedType.TEXT_OVERLAY);
        assertThat(p.escalate()).isTrue();
        assertThat(p.confidence()).isZero();
        assertThat(p.ownerRoster()).isEmpty();
        assertThat(p.markerClusters()).isEmpty();
        assertThat(p.itemCount()).isEqualTo(3);
    }

    @Test
    void differentLengthHashesDoNotCluster() {
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                item(id, "owner.acct", "0000"),
                item(id, "owner.acct", "0000"),   // clusters with the first (size 2)
                item(id, "member.a", "00000"));   // length 5 != 4 — must not join the cluster
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        DetectorProfileProposal p = service.propose(id);

        assertThat(p.proposedType()).isEqualTo(ProposedType.FLAT_BANNER);
        assertThat(p.markerClusters()).hasSize(1);
        assertThat(p.markerClusters().get(0).size()).isEqualTo(2);
        assertThat(p.confidence()).isEqualTo(2.0 / 3.0);
    }
}
