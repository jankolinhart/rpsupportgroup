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
 * Unit test for the Tier-0 (dHash-cluster) proposal engine and its M2 slam-dunk gate: a single owner that recurs on a
 * regular cadence spanning the grid, clearly ahead of the field, is ACCEPTED as FLAT_BANNER (no AI); a weak/ambiguous
 * grid keeps the ranked roster but ESCALATES (confidence = how far the top candidate separates from the second);
 * multi-author lookalike clusters and collab fan-out are rejected (impure / not distinct); a grid where nothing recurs
 * escalates as TEXT_OVERLAY; empty is UNKNOWN; the length guard keeps differently-sized hashes apart; an unknown
 * snapshot is a 404.
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
        // threshold 2, min-cluster 2, max-clusters 5, max-samples 5, min-purity 0.75, min-score 2.0, min-separation 0.5
        service = new VettingProposalService(snapshots, items, new NoOpAiVettingEnricher(), 2, 2, 5, 5, 0.75, 2.0, 0.5);
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

    /** Explicit-ordinal grid item — for cadence/coverage/separation tests where the position in the grid matters. */
    private CorpusSnapshotItem at(UUID snapshotId, String author, String dHash, int ord) {
        return CorpusSnapshotItem.of(snapshotId, "sc-" + ord, author, dHash, null, ord);
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
    void slamDunkSingleOwnerIsAcceptedWithoutAi() {
        // One owner re-posts the SAME banner 5x at a regular cadence spanning the grid; a reposter's different image
        // appears twice, bunched at the end. The owner's score towers over the reposter's, so the gate ACCEPTS one
        // owner (no AI) and names it alone.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                at(id, "owner.acct", H0, 0), at(id, "owner.acct", H0, 1), at(id, "owner.acct", H0, 2),
                at(id, "owner.acct", H0, 3), at(id, "owner.acct", H0, 4),
                at(id, "reposter.acct", H_FAR, 5), at(id, "reposter.acct", H_FAR, 6));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        DetectorProfileProposal p = service.propose(id);

        assertThat(p.proposedType()).isEqualTo(ProposedType.FLAT_BANNER);
        assertThat(p.escalate()).isFalse();                          // slam dunk — accepted, no AI
        assertThat(p.ownerRoster()).containsExactly("owner.acct");   // names the ONE owner
        assertThat(p.confidence()).isGreaterThan(0.9);               // ~0.95 separation from the reposter
        assertThat(p.itemCount()).isEqualTo(7);
        assertThat(p.markerClusters()).hasSize(2);                   // owner + reposter (context)
        DetectorProfileProposal.MarkerCluster top = p.markerClusters().get(0);
        assertThat(top.size()).isEqualTo(5);
        assertThat(top.dHash()).isEqualTo(H0);
        assertThat(top.authorUsernames()).containsExactly("owner.acct");
        assertThat(top.sampleShortcodes()).hasSize(5);
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
        assertThat(p.markerClusters()).hasSize(1);                 // the length-5 hash did NOT join the length-4 cluster
        assertThat(p.markerClusters().get(0).size()).isEqualTo(2);
        assertThat(p.escalate()).isTrue();                         // only 2 recurrences, low coverage → weak → escalate
        assertThat(p.confidence()).isEqualTo(0.25);
    }

    @Test
    void multiAuthorLookalikeClusterIsRejected() {
        // Three DIFFERENT members each post one near-identical image (a lookalike cluster, not a marker). The cluster
        // recurs (size 3) but its purity is 1/3, so it is discarded and Tier 0 escalates instead of flooding the roster.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                item(id, "member.a", H0),
                item(id, "member.b", H0_NEAR),
                item(id, "member.c", H0));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        DetectorProfileProposal p = service.propose(id);

        assertThat(p.proposedType()).isEqualTo(ProposedType.TEXT_OVERLAY);
        assertThat(p.escalate()).isTrue();
        assertThat(p.ownerRoster()).isEmpty();
        assertThat(p.markerClusters()).isEmpty();
        assertThat(p.confidence()).isZero();
    }

    @Test
    void collabPostIsNotCountedAsRecurrence() {
        // One collaborative post (a single shortcode) fanned into two author rows must NOT look like a 2-post cluster:
        // it is one distinct post, below min-cluster-size, so nothing is proposed.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                CorpusSnapshotItem.of(id, "collab-1", "owner.acct", H0, null, 0),
                CorpusSnapshotItem.of(id, "collab-1", "coauthor.acct", H0, null, 1));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        DetectorProfileProposal p = service.propose(id);

        assertThat(p.proposedType()).isEqualTo(ProposedType.TEXT_OVERLAY);
        assertThat(p.escalate()).isTrue();
        assertThat(p.ownerRoster()).isEmpty();
    }

    @Test
    void weakSingleOwnerEscalatesWithRosterKept() {
        // A pure single owner, but only 2 recurrences (cadence unprovable, strength below min-score) — not a slam dunk.
        // The gate keeps the owner on the roster as context but ESCALATES rather than auto-accepting a weak signal.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                at(id, "owner.acct", H0, 0), at(id, "owner.acct", H0, 1));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        DetectorProfileProposal p = service.propose(id);

        assertThat(p.proposedType()).isEqualTo(ProposedType.FLAT_BANNER);
        assertThat(p.escalate()).isTrue();
        assertThat(p.ownerRoster()).containsExactly("owner.acct");
        assertThat(p.confidence()).isEqualTo(0.5);   // strength min(1, score 1.0 / min-score 2.0)
        assertThat(p.markerClusters()).hasSize(1);
    }

    @Test
    void ambiguousEqualOwnersEscalateWithRankedRoster() {
        // Two equally-strong cadenced owners (interleaved every other post): both recur 3x, regular, same coverage, so
        // neither separates. separation = 0 -> confidence 0 -> ESCALATE, keeping BOTH on the roster (P4: co-owners) in a
        // deterministic (score, then author) order for the AI/human to adjudicate.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                at(id, "owner.a", H0, 0), at(id, "owner.b", H_FAR, 1),
                at(id, "owner.a", H0, 2), at(id, "owner.b", H_FAR, 3),
                at(id, "owner.a", H0, 4), at(id, "owner.b", H_FAR, 5));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        DetectorProfileProposal p = service.propose(id);

        assertThat(p.proposedType()).isEqualTo(ProposedType.FLAT_BANNER);
        assertThat(p.escalate()).isTrue();
        assertThat(p.ownerRoster()).containsExactly("owner.a", "owner.b");
        assertThat(p.confidence()).isEqualTo(0.0);   // no separation — a tie
        assertThat(p.markerClusters()).hasSize(2);
    }
}
