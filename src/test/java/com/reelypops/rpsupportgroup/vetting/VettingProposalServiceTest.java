package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.corpus.CorpusRepresentativeRepository;
import com.reelypops.rpsupportgroup.corpus.CorpusSnapshotItem;
import com.reelypops.rpsupportgroup.corpus.CorpusSnapshotItemRepository;
import com.reelypops.rpsupportgroup.corpus.CorpusSource;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusSnapshot;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusSnapshotRepository;
import com.reelypops.rpsupportgroup.group.DetectedProfile.ScheduleFacet;
import com.reelypops.rpsupportgroup.group.MarkerGroupType;
import com.reelypops.rpsupportgroup.group.RoundState;
import com.reelypops.rpsupportgroup.vetting.DetectorProfileProposal.ProposedType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the Tier-0 (dHash-cluster) proposal engine and its M6 owner-SET gate: an owner (or a co-owner SET on a
 * duty rota) that recurs on a regular cadence spanning the grid is ACCEPTED as FLAT_BANNER (no AI); a weak grid keeps
 * the repeat contributors as a roster but ESCALATES (confidence = the top cluster's repeat-contributor coverage x its
 * marker strength);
 * multi-author lookalike clusters and collab fan-out are rejected (no repeat contributors / not distinct); a grid where
 * nothing recurs escalates as TEXT_OVERLAY; empty is UNKNOWN; the length guard keeps differently-sized hashes apart; an
 * unknown snapshot is a 404.
 */
class VettingProposalServiceTest {

    private static final String H0 = "00000000";
    private static final String H0_NEAR = "00000001"; // Hamming 1 from H0
    private static final String H_FAR = "11111111";   // Hamming 8 from H0
    private static final String H_FAR2 = "00001111";  // Hamming 4 from H0, 4 from H_FAR

    private MarkerCorpusSnapshotRepository snapshots;
    private CorpusSnapshotItemRepository items;
    private CorpusRepresentativeRepository representatives;
    private VettingProposalService service;
    private final AtomicInteger ordinal = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        snapshots = mock(MarkerCorpusSnapshotRepository.class);
        items = mock(CorpusSnapshotItemRepository.class);
        representatives = mock(CorpusRepresentativeRepository.class);
        when(representatives.findShortcodesBySnapshotId(any())).thenReturn(List.of());
        // threshold 2, min-cluster 2, max-clusters 5, max-samples 5, min-coverage 0.75, repeat-contributor-min 2,
        // min-score 2.0, timing-merge tolerance 45min, min-concentration 0.85, max-hamming 5 (only near-identical banners merge)
        service = new VettingProposalService(snapshots, items, representatives, new NoOpAiVettingEnricher(), 2, 2, 5, 5, 0.75, 2, 2.0, 45, 0.85, 5);
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
        // appears twice, bunched at the end. The owner's cluster reads as a strong marker (score >= minScore) with full
        // repeat-contributor coverage, so the gate ACCEPTS the owner SET; the reposter's weak signal stays out of it.
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
        assertThat(p.confidence()).isGreaterThan(0.9);               // coverage 1.0 x full strength
        assertThat(p.itemCount()).isEqualTo(7);
        assertThat(p.markerClusters()).hasSize(2);                   // owner + reposter (context)
        DetectorProfileProposal.MarkerCluster top = p.markerClusters().get(0);
        assertThat(top.size()).isEqualTo(5);
        assertThat(top.dHash()).isEqualTo(H0);
        assertThat(top.authorUsernames()).containsExactly("owner.acct");
        assertThat(top.sampleShortcodes()).hasSize(5);
    }

    @Test
    void twoMarkerOwnerIsAcceptedDespiteTwoTopClusters() {
        // A 2-marker owner posts a START banner AND an END banner each round, so it produces the top TWO clusters (both
        // owner.acct). Both clusters read as strong markers, so their repeat contributors UNION to a single-member owner
        // SET {owner.acct}; the weak reposter never clears minScore, so it is not added.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                at(id, "owner.acct", H0, 0), at(id, "owner.acct", H_FAR, 1),
                at(id, "owner.acct", H0, 2), at(id, "owner.acct", H_FAR, 3),
                at(id, "owner.acct", H0, 4), at(id, "owner.acct", H_FAR, 5),
                at(id, "owner.acct", H0, 6), at(id, "owner.acct", H_FAR, 7),
                at(id, "reposter.acct", H_FAR2, 8), at(id, "reposter.acct", H_FAR2, 9));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        DetectorProfileProposal p = service.propose(id);

        assertThat(p.proposedType()).isEqualTo(ProposedType.FLAT_BANNER);
        assertThat(p.escalate()).isFalse();                          // owner does not tie with itself
        assertThat(p.ownerRoster()).containsExactly("owner.acct");   // ONE owner, despite two marker clusters
        assertThat(p.confidence()).isGreaterThan(0.9);               // coverage 1.0 x full strength
        assertThat(p.markerClusters()).hasSize(3);                   // START + END + reposter (context)
        assertThat(p.markerClusters().get(0).authorUsernames()).containsExactly("owner.acct");
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
    void concurrentCoOwnersWithDifferentBannersAcceptedAsSet() {
        // A duty rota where the two owners post their OWN banner (owner.a -> H0, owner.b -> H_FAR), alternating each
        // round: two strong single-owner clusters. There is no owner to "separate" from — they are CO-OWNERS, not
        // rivals — so M6 ACCEPTS the owner SET (both) instead of escalating on the tie (vision [DECIDED 5.8]).
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
        assertThat(p.escalate()).isFalse();                                  // co-owners are a SET, not a tie to escalate
        assertThat(p.ownerRoster()).containsExactlyInAnyOrder("owner.a", "owner.b");
        assertThat(p.confidence()).isGreaterThan(0.9);                       // strong markers, full coverage
        assertThat(p.markerClusters()).hasSize(2);
    }

    @Test
    void sharedBannerCoOwnersAcceptedAsSet() {
        // The MOTIVATING case (dailyblogger___): two owners on a duty rota SHARE ONE banner (both post H0), alternating
        // each round. Perceptual clustering merges them into a SINGLE cluster whose single-author purity is ~0.5 — which
        // ESCALATED under the old purity gate. M6's repeat-contributor coverage sees BOTH as repeat contributors (each
        // >= 2 posts) covering the whole cluster, so it ACCEPTS the owner SET = both (vision [DECIDED 5.8]).
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                at(id, "owner.a", H0, 0), at(id, "owner.b", H0, 1),
                at(id, "owner.a", H0, 2), at(id, "owner.b", H0, 3),
                at(id, "owner.a", H0, 4), at(id, "owner.b", H0, 5));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        DetectorProfileProposal p = service.propose(id);

        assertThat(p.proposedType()).isEqualTo(ProposedType.FLAT_BANNER);
        assertThat(p.escalate()).isFalse();                                  // coverage sees both owners → accept
        assertThat(p.ownerRoster()).containsExactlyInAnyOrder("owner.a", "owner.b");
        assertThat(p.confidence()).isGreaterThan(0.9);
        assertThat(p.markerClusters()).hasSize(1);                           // ONE shared banner cluster
    }

    @Test
    void singleMarkerMultiOwnerDerivesOwnerSetAndMaxTagged() {
        // A SINGLE-marker duty rota: two co-owners share ONE marker banner (H0), taking turns each round, with members
        // posting between markers. The owner SET is both; because there is ONE marker cluster the group type is
        // SINGLE_MARKER (owner-set is DECOUPLED from group type); and maxTaggedPosts is counted across rounds bounded by
        // ANY owner's markers (M6 union, directive P4).
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                at(id, "owner.a", H0, 0),                                     // marker (round boundary)
                at(id, "member.x", "00110011", 1),                           // member post in round
                at(id, "owner.b", H0, 2),                                     // marker (other co-owner)
                at(id, "member.y", "01010101", 3),
                at(id, "owner.a", H0, 4),                                     // marker
                at(id, "member.z", "00111100", 5),
                at(id, "owner.b", H0, 6));                                    // marker
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        SnapshotAnalysis a = service.analyze(id);

        assertThat(a.proposal().escalate()).isFalse();
        assertThat(a.proposal().ownerRoster()).containsExactlyInAnyOrder("owner.a", "owner.b");
        assertThat(a.schedule().groupType()).isEqualTo(MarkerGroupType.SINGLE_MARKER);
        assertThat(a.schedule().maxTaggedPosts()).isEqualTo(1);              // one member post per round
    }

    @Test
    void reScreenshotVariantsAreTimingMergedIntoOneMarker() {
        // P1.5: a manager re-screenshots the owner's banner — visually SIMILAR (the dHash drifts only a few bits, within
        // the timing-merge ceiling) but past the clustering threshold, SPLITTING one marker into two clusters (owner.a ->
        // H0, owner.b -> H_FAR2, 4 bits apart). Both are posted at the SAME time-of-day (~09:00), so the TIMING MERGE
        // reunites them into ONE marker: the owner SET recovers both, and the group reads SINGLE_MARKER (not a phantom two).
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                dated(id, "owner.a", H0, 0, "2026-01-04T09:00:00Z"),
                dated(id, "owner.b", H_FAR2, 1, "2026-01-05T09:04:00Z"),
                dated(id, "owner.a", H0, 2, "2026-01-06T09:01:00Z"),
                dated(id, "owner.b", H_FAR2, 3, "2026-01-07T08:59:00Z"),
                dated(id, "owner.a", H0, 4, "2026-01-08T09:02:00Z"),
                dated(id, "owner.b", H_FAR2, 5, "2026-01-09T09:00:00Z"));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        SnapshotAnalysis a = service.analyze(id);

        assertThat(a.proposal().escalate()).isFalse();
        assertThat(a.proposal().ownerRoster()).containsExactlyInAnyOrder("owner.a", "owner.b");
        assertThat(a.proposal().markerClusters()).hasSize(1);               // the two variants merged into ONE marker
        assertThat(a.schedule().groupType()).isEqualTo(MarkerGroupType.SINGLE_MARKER);
    }

    @Test
    void timingMergeDisabledByNonPositiveToleranceLeavesVariantsSplit() {
        // With the timing merge OFF (tolerance 0), the two re-screenshot variants stay separate clusters. P1's owner SET
        // still unions them (across the two strong clusters), but the group now over-reads as TWO_MARKER — the very
        // over-count the merge exists to prevent. Guards the kill switch + shows the merge changes the outcome.
        VettingProposalService noMerge = new VettingProposalService(snapshots, items, representatives,
                new NoOpAiVettingEnricher(), 2, 2, 5, 5, 0.75, 2, 2.0, 0, 0.85, 5);
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                dated(id, "owner.a", H0, 0, "2026-01-04T09:00:00Z"),
                dated(id, "owner.b", H_FAR2, 1, "2026-01-05T09:04:00Z"),
                dated(id, "owner.a", H0, 2, "2026-01-06T09:01:00Z"),
                dated(id, "owner.b", H_FAR2, 3, "2026-01-07T08:59:00Z"),
                dated(id, "owner.a", H0, 4, "2026-01-08T09:02:00Z"),
                dated(id, "owner.b", H_FAR2, 5, "2026-01-09T09:00:00Z"));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        SnapshotAnalysis a = noMerge.analyze(id);

        assertThat(a.proposal().ownerRoster()).containsExactlyInAnyOrder("owner.a", "owner.b");
        assertThat(a.proposal().markerClusters()).hasSize(2);               // split — not merged
        assertThat(a.schedule().groupType()).isEqualTo(MarkerGroupType.TWO_MARKER);
    }

    @Test
    void differentBannersPostedAtTheSameTimeAreNotTimingMerged() {
        // The dailyblogger___ case: the START and END banners are DIFFERENT images (owner.a -> H0, owner.b -> H_FAR, 8
        // bits apart — well beyond a re-screenshot drift) but the group posts them TOGETHER at the same time (~18:35).
        // The timing merge must NOT fuse them on time alone (that would collapse TWO_MARKER to SINGLE_MARKER); the visual
        // guard keeps the two distinct banners apart, so the group reads TWO_MARKER with both markers + both owners.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                dated(id, "owner.a", H0, 0, "2026-01-04T18:35:00Z"),
                dated(id, "owner.b", H_FAR, 1, "2026-01-04T18:36:00Z"),
                dated(id, "owner.a", H0, 2, "2026-01-05T18:35:00Z"),
                dated(id, "owner.b", H_FAR, 3, "2026-01-05T18:36:00Z"),
                dated(id, "owner.a", H0, 4, "2026-01-06T18:35:00Z"),
                dated(id, "owner.b", H_FAR, 5, "2026-01-06T18:36:00Z"));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        SnapshotAnalysis a = service.analyze(id);

        assertThat(a.proposal().escalate()).isFalse();
        assertThat(a.proposal().ownerRoster()).containsExactlyInAnyOrder("owner.a", "owner.b");
        assertThat(a.proposal().markerClusters()).hasSize(2);              // START + END stay distinct
        assertThat(a.schedule().groupType()).isEqualTo(MarkerGroupType.TWO_MARKER);
    }

    /** Grid item WITH a posted-at instant — for the M3b schedule derivation (times / weekdays / current state). */
    private CorpusSnapshotItem dated(UUID snapshotId, String author, String dHash, int ord, String instant) {
        return CorpusSnapshotItem.of(snapshotId, "sc-" + ord, author, dHash, Instant.parse(instant), ord);
    }

    @Test
    void referenceSamplesPreferShortcodesWithACapturedImage() {
        // The captured representative of a cluster is often not its first post by grid order — the reference must
        // surface a shortcode that actually has an image so the admin sees a thumbnail, not a 404.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                at(id, "owner.acct", H0, 0), at(id, "owner.acct", H0, 1), at(id, "owner.acct", H0, 2));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);
        // Only the THIRD post (sc-2) has a captured representative image.
        when(representatives.findShortcodesBySnapshotId(id)).thenReturn(List.of("sc-2"));

        SnapshotAnalysis a = service.analyze(id);

        assertThat(a.references()).singleElement().satisfies(r ->
                assertThat(r.sampleShortcodes().get(0)).isEqualTo("sc-2")); // image-backed shortcode first
    }

    @Test
    void analyzeEnrichesWithCandidateMetricsReferenceConfidenceAndDerivedSchedule() {
        // A clean 2-marker owner: a START banner (H0, Sun 09:00) and an END banner (H_FAR, the following Sat 18:00)
        // across four weekly rounds. The open round spans Sun→Sat, so the open days are the whole week (incl Sunday).
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                dated(id, "owner.acct", H0, 0, "2026-01-04T09:00:00Z"),    // Sun START
                dated(id, "owner.acct", H_FAR, 1, "2026-01-10T18:00:00Z"), // Sat END
                dated(id, "owner.acct", H0, 2, "2026-01-11T09:00:00Z"),
                dated(id, "owner.acct", H_FAR, 3, "2026-01-17T18:00:00Z"),
                dated(id, "owner.acct", H0, 4, "2026-01-18T09:00:00Z"),
                dated(id, "owner.acct", H_FAR, 5, "2026-01-24T18:00:00Z"),
                dated(id, "owner.acct", H0, 6, "2026-01-25T09:00:00Z"),
                dated(id, "owner.acct", H_FAR, 7, "2026-01-31T18:00:00Z"));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        SnapshotAnalysis a = service.analyze(id);

        assertThat(a.proposal().proposedType()).isEqualTo(ProposedType.FLAT_BANNER);
        assertThat(a.proposal().escalate()).isFalse();
        assertThat(a.proposal().ownerRoster()).containsExactly("owner.acct");
        // Two owner clusters (START + END): each a candidate with metrics, each a reference with marker confidence.
        assertThat(a.candidates()).hasSize(2);
        assertThat(a.candidates()).allSatisfy(c -> assertThat(c.author()).isEqualTo("owner.acct"));
        assertThat(a.references()).hasSize(2);
        assertThat(a.references()).allSatisfy(r -> assertThat(r.confidence()).isEqualTo(1.0));
        ScheduleFacet s = a.schedule();
        assertThat(s.groupType()).isEqualTo(MarkerGroupType.TWO_MARKER);
        assertThat(s.groupTypeConfidence()).isGreaterThan(0.9);
        assertThat(s.start().timeOfDayUtc()).isEqualTo("09:00");
        assertThat(s.end().timeOfDayUtc()).isEqualTo("18:00");
        assertThat(s.openWeekdays()).containsExactly(0, 1, 2, 3, 4, 5, 6); // the round spans the whole week
        assertThat(s.endMarkerDayOffset()).isEqualTo(6);                   // Sun → the following Sat
        assertThat(s.currentState()).isEqualTo(RoundState.CLOSED_PERIOD);  // trailing marker is an END
    }

    @Test
    void aiSamplesCarryTheOwnersFullBannerSetIncludingSubThresholdFragments() {
        // The proposed owner posts a STRONG clean marker (H0, four posts spanning the grid → score ≥ minScore, so a
        // reference) AND a WEAK fragment (H_FAR, two bunched posts → score < minScore, NOT a reference). The broadened
        // AI sample must carry BOTH, so a high-variation banner that shattered below the gate still reaches the model.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                at(id, "owner.acct", H0, 0), at(id, "owner.acct", H0, 1),
                at(id, "owner.acct", H0, 2), at(id, "owner.acct", H0, 3),        // strong: 4 posts spanning → score 2.4
                at(id, "owner.acct", H_FAR, 4), at(id, "owner.acct", H_FAR, 5)); // weak: 2 bunched posts → score 0.2
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        SnapshotAnalysis a = service.analyze(id);

        // The score gate keeps only the strong cluster as a reference…
        assertThat(a.references()).singleElement().satisfies(r -> assertThat(r.confidence()).isEqualTo(1.0));
        // …but the AI sample carries BOTH the strong marker and the sub-threshold fragment, all the proposed owner's.
        assertThat(a.aiSamples()).hasSize(2);
        assertThat(a.aiSamples()).allSatisfy(s -> {
            assertThat(s.author()).isEqualTo("owner.acct");
            assertThat(s.sampleShortcodes()).isNotEmpty();
        });
        assertThat(a.aiSamples()).anyMatch(s -> s.score() >= 2.0); // the reference-grade marker
        assertThat(a.aiSamples()).anyMatch(s -> s.score() < 2.0);  // the fragment the gate dropped but the AI now sees
    }

    @Test
    void aiSamplesIncludeCapturedOwnerSingletonsTheClusterGateDrops() {
        // The proposed owner posts a STRONG clean marker (H0 ×3 → a size>=min-cluster candidate) plus a low-recurrence
        // weekend VARIANT banner that appears ONCE over a unique background (H_FAR2 ×1): a dHash SINGLETON the
        // size>=min-cluster gate drops from the candidates. Because that singleton is a captured marker candidate (it
        // carries a representative image the admin sees in the grid), the broadened AI sample must still carry it — else a
        // "START Sonntag" posted once never reaches the vision model. An image-LESS owner singleton (H_FAR ×1) stays out.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                at(id, "owner.acct", H0, 0), at(id, "owner.acct", H0, 1), at(id, "owner.acct", H0, 2), // strong marker
                at(id, "owner.acct", H_FAR2, 3), // captured singleton variant (sc-3) — dropped by the size gate
                at(id, "owner.acct", H_FAR, 4));  // image-less singleton (sc-4) — must NOT reach the AI
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);
        when(representatives.findShortcodesBySnapshotId(id)).thenReturn(List.of("sc-3")); // only the variant is captured

        SnapshotAnalysis a = service.analyze(id);

        // The size>=min-cluster gate keeps only the strong H0 marker as a scored candidate…
        assertThat(a.candidates()).singleElement().satisfies(c -> assertThat(c.author()).isEqualTo("owner.acct"));
        // …but the AI sample carries the strong marker AND the captured singleton variant (not the image-less singleton).
        assertThat(a.aiSamples()).hasSize(2);
        assertThat(a.aiSamples()).allSatisfy(s -> assertThat(s.author()).isEqualTo("owner.acct"));
        assertThat(a.aiSamples()).anyMatch(s -> s.sampleShortcodes().contains("sc-3")); // captured variant reaches the AI
        assertThat(a.aiSamples()).noneMatch(s -> s.sampleShortcodes().contains("sc-4")); // image-less singleton does not
    }

    @Test
    void gridWindowPreservesGridOrderAndFlagsTheOwnersMarkers() {
        // The AI reconstructs rounds from the grid IN ORDER with the owner's markers flagged; members are the rows between.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = List.of(
                at(id, "owner.acct", H0, 0), at(id, "alice", H_FAR, 1), at(id, "bob", H_FAR2, 2),
                at(id, "owner.acct", H0, 3), at(id, "owner.acct", H0, 4));
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        SnapshotAnalysis a = service.analyze(id);

        assertThat(a.gridWindow()).extracting(GridRow::ordinal).containsExactly(0, 1, 2, 3, 4);
        assertThat(a.gridWindow()).extracting(GridRow::author)
                .containsExactly("owner.acct", "alice", "bob", "owner.acct", "owner.acct");
        assertThat(a.gridWindow()).extracting(GridRow::marker)
                .containsExactly(true, false, false, true, true); // only the proposed owner's posts are markers
    }

    @Test
    void gridWindowStopsAtTheMarkerCap() {
        // A long grid is windowed to the most recent markers so the request can't blow up (GRID_WINDOW_MARKERS = 12).
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            grid.add(at(id, "owner.acct", H0, i)); // 15 owner markers, more than the window cap
        }
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        SnapshotAnalysis a = service.analyze(id);

        assertThat(a.gridWindow()).hasSize(12);                          // capped at GRID_WINDOW_MARKERS
        assertThat(a.gridWindow()).allSatisfy(r -> assertThat(r.marker()).isTrue());
        assertThat(a.gridWindow().get(11).ordinal()).isEqualTo(11);     // the 12th marker is the last row included
    }

    @Test
    void gridWindowEndsOnAMarkerNotMidRound() {
        // Round-complete: the window must end on a marker (a round boundary), never on a member row — else the oldest
        // round's START (the bottom-most post) is cut off, the missing-Sunday-START symptom. Owner markers interleaved
        // with member posts; the window ends exactly on the 12th owner marker, its trailing member excluded.
        MarkerCorpusSnapshot snap = MarkerCorpusSnapshot.open("glow.grp", CorpusSource.REQUEST, "cap.acct");
        UUID id = snap.getId();
        List<CorpusSnapshotItem> grid = new ArrayList<>();
        int ord = 0;
        for (int round = 0; round < 14; round++) {          // 14 owner markers (more than the cap)...
            grid.add(at(id, "owner.acct", H0, ord++));       // ...each a marker; members only in the first rounds so
            if (round < 6) {                                 // the owner clearly dominates (14 posts vs 6).
                grid.add(at(id, "member.acct", H_FAR, ord++));
            }
        }
        when(snapshots.findById(id)).thenReturn(Optional.of(snap));
        when(items.findBySnapshotIdOrderByOrdinalAsc(id)).thenReturn(grid);

        SnapshotAnalysis a = service.analyze(id);

        assertThat(a.gridWindow().stream().filter(GridRow::marker).count()).isEqualTo(12L); // spanned the marker cap
        assertThat(a.gridWindow().get(a.gridWindow().size() - 1).marker()).isTrue();        // ends ON a marker, not mid-round
    }
}
