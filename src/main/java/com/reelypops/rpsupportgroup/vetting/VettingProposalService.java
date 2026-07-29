package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.corpus.CorpusRepresentativeRepository;
import com.reelypops.rpsupportgroup.corpus.CorpusSnapshotItem;
import com.reelypops.rpsupportgroup.corpus.CorpusSnapshotItemRepository;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusSnapshot;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusSnapshotRepository;
import com.reelypops.rpsupportgroup.group.DetectedProfile.MarkerReference;
import com.reelypops.rpsupportgroup.group.DetectedProfile.OwnerCandidate;
import com.reelypops.rpsupportgroup.group.DetectedProfile.ScheduleFacet;
import com.reelypops.rpsupportgroup.vetting.DetectorProfileProposal.MarkerCluster;
import com.reelypops.rpsupportgroup.vetting.DetectorProfileProposal.ProposedType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tier&nbsp;0 of the P3 vetting pipeline: cheap, local, no-AI. It clusters a sealed snapshot's grid items by perceptual
 * (dHash) similarity to spot a recurring marker banner, derives the candidate owner roster + a confidence, and hands the
 * result to the {@link AiVettingEnricher} seam (a no-op by default) for optional Tier&nbsp;1/2 refinement. The output is
 * an <strong>advisory</strong> {@link DetectorProfileProposal} — it pre-fills the admin form but never auto-vets.
 */
@Service
public class VettingProposalService {

    private static final String PROVENANCE_TIER0 = "TIER_0_DHASH";
    // Cadence can't be judged from fewer than 3 recurrences (2 posts = 1 gap = no variance), so such clusters get a
    // neutral, non-committal cadence rather than a falsely-perfect one.
    private static final double NEUTRAL_CADENCE = 0.5;

    // M4.7/M4.8: how much of the recent tagged grid to hand the AI as an ordered window — enough recent owner markers to
    // reconstruct several rounds (each round is ~2 markers). The row cap is a SOFT bound (the window still extends to the
    // next marker to close a round) that only guards a pathologically member-heavy grid.
    private static final int GRID_WINDOW_MARKERS = 12;
    private static final int GRID_WINDOW_MAX_ROWS = 800;

    private static final Logger log = LoggerFactory.getLogger(VettingProposalService.class);

    private final MarkerCorpusSnapshotRepository snapshots;
    private final CorpusSnapshotItemRepository items;
    private final CorpusRepresentativeRepository representatives;
    private final AiVettingEnricher enricher;
    private final int hammingThreshold;
    private final int minClusterSize;
    private final int maxClusters;
    private final int maxSamples;
    private final double minCoverage;
    private final int repeatContributorMin;
    private final double minScore;
    private final int timingMergeToleranceMinutes;
    private final double timingMergeMinConcentration;
    private final int timingMergeMaxHamming;

    public VettingProposalService(MarkerCorpusSnapshotRepository snapshots, CorpusSnapshotItemRepository items,
                                  CorpusRepresentativeRepository representatives,
                                  AiVettingEnricher enricher,
                                  @Value("${rp.vetting.dhash-threshold:4}") int hammingThreshold,
                                  @Value("${rp.vetting.min-cluster-size:2}") int minClusterSize,
                                  @Value("${rp.vetting.max-clusters:5}") int maxClusters,
                                  @Value("${rp.vetting.max-samples:5}") int maxSamples,
                                  @Value("${rp.vetting.min-coverage:0.75}") double minCoverage,
                                  @Value("${rp.vetting.repeat-contributor-min:2}") int repeatContributorMin,
                                  @Value("${rp.vetting.min-score:2.0}") double minScore,
                                  @Value("${rp.vetting.timing-merge-tolerance-minutes:45}") int timingMergeToleranceMinutes,
                                  @Value("${rp.vetting.timing-merge-min-concentration:0.85}") double timingMergeMinConcentration,
                                  @Value("${rp.vetting.timing-merge-max-hamming:10}") int timingMergeMaxHamming) {
        this.snapshots = snapshots;
        this.items = items;
        this.representatives = representatives;
        this.enricher = enricher;
        this.hammingThreshold = hammingThreshold;
        this.minClusterSize = minClusterSize;
        this.maxClusters = maxClusters;
        this.maxSamples = maxSamples;
        this.minCoverage = minCoverage;
        this.repeatContributorMin = repeatContributorMin;
        this.minScore = minScore;
        this.timingMergeToleranceMinutes = timingMergeToleranceMinutes;
        this.timingMergeMinConcentration = timingMergeMinConcentration;
        this.timingMergeMaxHamming = timingMergeMaxHamming;
    }

    /** Build the advisory vetting proposal for a snapshot (404 if unknown), then run it through the enricher seam. */
    @Transactional(readOnly = true)
    public DetectorProfileProposal propose(UUID snapshotId) {
        return analyze(snapshotId).proposal();
    }

    /**
     * The full Tier&nbsp;0 read of a snapshot (M3b): the enriched {@link DetectorProfileProposal} plus the ranked owner
     * candidates, the recurring-image references (with marker confidence), and the derived schedule — computed in one
     * clustering pass and mapped onto the persisted {@link com.reelypops.rpsupportgroup.group.DetectedProfile} advisory.
     */
    @Transactional(readOnly = true)
    public SnapshotAnalysis analyze(UUID snapshotId) {
        MarkerCorpusSnapshot snapshot = snapshots.findById(snapshotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no snapshot " + snapshotId));
        List<CorpusSnapshotItem> gridItems = items.findBySnapshotIdOrderByOrdinalAsc(snapshotId);
        Set<String> withImages = new HashSet<>(representatives.findShortcodesBySnapshotId(snapshotId));
        SnapshotAnalysis analysis = buildAnalysis(snapshot, gridItems, withImages);
        DetectorProfileProposal enriched = enricher.enrich(analysis.proposal(), gridItems);
        return new SnapshotAnalysis(enriched, analysis.candidates(), analysis.references(), analysis.schedule(),
                analysis.referencePostedAt(), analysis.aiSamples(), analysis.gridWindow());
    }

    private SnapshotAnalysis buildAnalysis(MarkerCorpusSnapshot snapshot, List<CorpusSnapshotItem> gridItems,
                                           Set<String> withImages) {
        UUID id = snapshot.getId();
        String ig = snapshot.getIgAccount();
        int itemCount = gridItems.size();
        if (itemCount == 0) {
            return emptyAnalysis(new DetectorProfileProposal(id, ig, 0, ProposedType.UNKNOWN, List.of(), List.of(),
                    0.0, false, PROVENANCE_TIER0));
        }
        List<Cluster> allClusters = mergeByTiming(cluster(gridItems));
        List<Cluster> strong = allClusters.stream().filter(c -> c.size() >= minClusterSize).toList();
        if (strong.isEmpty()) {
            // No image recurs across the grid: a text-overlay style — Tier 0 cannot judge it, so escalate to vision.
            return emptyAnalysis(new DetectorProfileProposal(id, ig, itemCount, ProposedType.TEXT_OVERLAY, List.of(),
                    List.of(), 0.0, true, PROVENANCE_TIER0));
        }
        // A genuine flat-banner marker is the SAME image re-posted across DISTINCT posts by its OWNER(s) (directive P5:
        // a post appears in the grid only once, so an image recurs only because an owner re-posts a fresh banner each
        // round). The owner may be a SET — a duty rota shares the posting — so keep clusters that (a) recur across
        // >= minClusterSize DISTINCT posts and (b) are dominated by REPEAT CONTRIBUTORS (repeatCoverage >= minCoverage:
        // the share of the cluster's posts by authors with >= repeatContributorMin posts). This admits shared-banner
        // co-owners (each recurs, so single-author purity ~0.5 but coverage is high) while still rejecting multi-author
        // lookalike floods (a long tail of one-offs => low coverage) and collab fan-out (one distinct post) — the two
        // things that flooded the roster with non-owners on large grids (M6, [DECIDED 5.8]).
        // Each surviving cluster is scored on its marker SIGNATURE (M2, "Fast M2"): recurrence x cadence-regularity x
        // coverage, measured on the REPEAT-CONTRIBUTOR (marker) posts — so a shared banner scores on the FULL round
        // cadence (every co-owner's post), not one owner's half. A real marker recurs at a REGULAR cadence spanning the
        // window; a serial re-poster does not.
        List<MarkerCandidate> candidates = strong.stream()
                .map(c -> MarkerCandidate.from(c.representative(), c.members(), maxSamples, itemCount, withImages,
                        repeatContributorMin))
                .filter(c -> c.distinctPosts() >= minClusterSize && c.repeatCoverage() >= minCoverage)
                .sorted(Comparator.comparingDouble(MarkerCandidate::score).reversed()
                        .thenComparing(MarkerCandidate::dominantAuthor))
                .toList();
        if (candidates.isEmpty()) {
            // Images recur, but none is dominated by repeat contributors (multi-author lookalike flood / collab
            // fan-out) — punt to vision rather than proposing a wrong owner.
            return emptyAnalysis(new DetectorProfileProposal(id, ig, itemCount, ProposedType.TEXT_OVERLAY, List.of(),
                    List.of(), 0.0, true, PROVENANCE_TIER0));
        }
        // The marker-owner SET gate (M6, [DECIDED 5.8]). A cluster reads as a real marker when its signature clears
        // minScore; the owner SET is the UNION of the repeat contributors across those strong clusters — so co-owners
        // on a duty rota (whether they SHARE one banner or post their OWN) are ALL admitted, never escalated between
        // (the old owner-to-owner separation is gone: two genuine co-owners are a SET, not rivals). Accept on a SLAM
        // DUNK — at least one cluster reads as a marker — else keep the repeat contributors as context but ESCALATE
        // (bias to escalate; the AI is effectively free). confidence = the top cluster's repeat-contributor coverage x
        // its normalised marker strength.
        MarkerCandidate top = candidates.get(0);
        List<MarkerCandidate> markerClusters = candidates.stream().filter(c -> c.score() >= minScore).toList();
        LinkedHashSet<String> ownerSet = new LinkedHashSet<>();
        markerClusters.forEach(c -> ownerSet.addAll(c.repeatContributors()));
        double strength = Math.min(1.0, top.score() / minScore);
        double confidence = top.repeatCoverage() * strength;
        boolean slamDunk = !ownerSet.isEmpty();
        logDiagnostics(id, itemCount, candidates, ownerSet, slamDunk, confidence);

        List<MarkerCluster> clusters = candidates.stream().limit(maxClusters).map(this::toMarkerCluster).toList();
        DetectorProfileProposal proposal;
        if (slamDunk) {
            proposal = new DetectorProfileProposal(id, ig, itemCount, ProposedType.FLAT_BANNER,
                    List.copyOf(ownerSet), clusters, confidence, false, PROVENANCE_TIER0);
        } else {
            // No cluster reads strongly enough as a marker — surface the repeat contributors (ranked) as context.
            List<String> roster = candidates.stream().flatMap(c -> c.repeatContributors().stream()).distinct().toList();
            proposal = new DetectorProfileProposal(id, ig, itemCount, ProposedType.FLAT_BANNER, roster, clusters,
                    confidence, true, PROVENANCE_TIER0);
        }
        // The effective owner set for the schedule + marker derivation: the accepted SET, or (on escalate) the best
        // candidate's repeat contributors so an advisory schedule is still derived from the strongest signal.
        Set<String> effectiveOwners = ownerSet.isEmpty() ? new LinkedHashSet<>(top.repeatContributors()) : ownerSet;
        // M3b + M6: surface the discovered owner SET as a per-OWNER contribution breakdown (who owns markers + their
        // duty share), NOT a per-cluster row — so a shared-banner rota shows EVERY co-owner (each a repeat contributor of
        // the accepted clusters) and a coincidental sub-strength lookalike cluster (whose dominant is not a real owner)
        // never appears. Aggregate over the accepted marker clusters when we owned the group, else every candidate
        // cluster when we escalated (context for the reviewer). Per-reference confidence is surfaced on the references.
        List<OwnerCandidate> ownerCandidates = ownerBreakdown(slamDunk ? markerClusters : candidates);
        // Only surface candidates that actually read as markers (score >= the strength threshold) as references, so a
        // coincidental lookalike cluster (a couple of near-dup member photos) is not shown as a "marker" with no image.
        // Fall back to the top clusters when nothing clears the bar (an escalated group still needs candidates to review).
        List<MarkerCandidate> referenceCandidates = candidates.stream()
                .filter(c -> c.score() >= minScore)
                .limit(maxClusters)
                .toList();
        if (referenceCandidates.isEmpty()) {
            referenceCandidates = candidates.stream().limit(maxClusters).toList();
        }
        List<MarkerReference> references = referenceCandidates.stream().map(c -> c.toReference(minScore)).toList();
        // M4.5: parallel to references, the sorted marker-post timestamps of each reference cluster — the AI's
        // per-weekday occurrence source (bucketed by weekday + time in AiDiscoveryService). Includes EVERY co-owner's
        // marker posts (markerPosts), not just the dominant author's.
        List<List<Instant>> referencePostedAt = referenceCandidates.stream()
                .map(c -> c.markerPosts().stream()
                        .map(ScheduleDeriver.Post::postedAt)
                        .filter(t -> t != null)
                        .sorted()
                        .toList())
                .toList();
        List<ScheduleDeriver.ClusterPosts> ownerClusters = candidates.stream()
                .filter(c -> effectiveOwners.contains(c.dominantAuthor()))
                .map(c -> new ScheduleDeriver.ClusterPosts(c.markerPosts()))
                .toList();
        // The FULL ordered grid (one row per post × author, directive P4) — not the capped AI window — so the
        // deterministic maxTaggedPosts count spans every round the corpus captured (vision §5.6). A row is a marker when
        // ANY member of the owner SET posted it (M6 union), so rounds bracket on any owner's markers.
        List<GridRow> allRows = gridItems.stream()
                .map(it -> new GridRow(it.getOrdinal(), it.getAuthorUsername(),
                        effectiveOwners.contains(it.getAuthorUsername())))
                .toList();
        ScheduleFacet schedule = ScheduleDeriver.derive(ownerClusters, allRows);
        logScheduleDiagnostics(id, effectiveOwners, ownerClusters, schedule);
        // M4.6/M4.10: the broadened marker sample for the AI — EVERY captured cluster of the proposed owner SET, not only
        // the score>=minScore references and not only the size>=minClusterSize candidates. A high-variation banner shatters
        // two ways: into sub-SCORE fragments (M4.6) AND into sub-SIZE dHash SINGLETONS — a weekend-only "START Sonntag" posted
        // once or twice over changing backgrounds never clusters, so the `strong` gate drops it and it never reaches the
        // model even though it is a preselected marker candidate the admin SEES in the corpus grid. Rebuild from ALL
        // clusters and keep every owner-set one that is EITHER a scored candidate (size>=minClusterSize, coverage>=minCoverage,
        // as before) OR carries a captured representative image (the dropped singleton variants); an image-less owner
        // singleton stays out (pure noise). AiDiscoveryService round-robins one image per cluster first, so the extra
        // low-score singletons here are not starved by the maxImages cap.
        List<AiMarkerSample> aiSamples = allClusters.stream()
                .map(c -> MarkerCandidate.from(c.representative(), c.members(), maxSamples, itemCount, withImages,
                        repeatContributorMin))
                .filter(c -> effectiveOwners.contains(c.dominantAuthor()))
                .filter(c -> (c.distinctPosts() >= minClusterSize && c.repeatCoverage() >= minCoverage)
                        || c.sampleShortcodes().stream().anyMatch(withImages::contains))
                .sorted(Comparator.comparingDouble(MarkerCandidate::score).reversed()
                        .thenComparing(MarkerCandidate::dominantAuthor))
                .map(MarkerCandidate::toAiSample)
                .toList();
        // M4.7: the recent tagged-grid window in true order — the owner's markers interleaved with member posts — so the
        // AI can reconstruct rounds from the sequence (START→ENDE pairs) and count per-round members (maxTaggedPosts).
        List<GridRow> gridWindow = buildGridWindow(gridItems, effectiveOwners);
        return new SnapshotAnalysis(proposal, ownerCandidates, references, schedule, referencePostedAt, aiSamples,
                gridWindow);
    }

    /** The analysis for a snapshot with no clean owner: just the proposal, no candidates / references / schedule. */
    private static SnapshotAnalysis emptyAnalysis(DetectorProfileProposal proposal) {
        return new SnapshotAnalysis(proposal, List.of(), List.of(), ScheduleDeriver.empty(), List.of(), List.of(),
                List.of());
    }

    /**
     * The most recent slice of the grid (newest tag first, directive P5) as an ordered {@link GridRow} window: each
     * (post × author) row is flagged {@code marker} when its author is in the proposed owner SET (M6). The window is
     * ROUND-COMPLETE — it only ends on a marker (a round boundary), never mid-round, so the oldest round's START is never
     * truncated below the edge (which would leave that round with an END but no START — the missing-Sunday-START symptom).
     * It spans up to {@link #GRID_WINDOW_MARKERS} owner markers; a pathologically member-heavy grid trips
     * {@link #GRID_WINDOW_MAX_ROWS} but still extends to the next marker to close the current round.
     */
    private static List<GridRow> buildGridWindow(List<CorpusSnapshotItem> gridItems, Set<String> owners) {
        List<GridRow> rows = new ArrayList<>();
        int markers = 0;
        boolean complete = false;
        for (int i = 0; i < gridItems.size() && !complete; i++) {
            CorpusSnapshotItem item = gridItems.get(i);
            boolean marker = owners.contains(item.getAuthorUsername());
            rows.add(new GridRow(item.getOrdinal(), item.getAuthorUsername(), marker));
            if (marker) {
                markers++;
                // Close the window only on a marker (a round boundary), once we've spanned enough markers or overrun
                // the soft row cap — so a round is never cut mid-way (which would drop the oldest round's START).
                complete = markers >= GRID_WINDOW_MARKERS || rows.size() >= GRID_WINDOW_MAX_ROWS;
            }
        }
        return rows;
    }

    /** Calibration diagnostic (M2): per-candidate marker-signature metrics + the accept/escalate decision (cloud log). */
    private void logDiagnostics(UUID id, int itemCount, List<MarkerCandidate> candidates, Set<String> ownerSet,
                                boolean slamDunk, double confidence) {
        log.info("VETTING_DIAG snapshot={} items={} decision={} confidence={} candidates=[{}]",
                id, itemCount, slamDunk ? "ACCEPT:" + ownerSet : "ESCALATE",
                String.format("%.2f", confidence),
                candidates.stream().map(c -> String.format("%s{rec=%d,rptCov=%.2f,cadence=%.2f,coverage=%.2f,score=%.2f}",
                        c.dominantAuthor(), c.recurrence(), c.repeatCoverage(), c.cadenceRegularity(), c.coverage(),
                        c.score()))
                        .collect(Collectors.joining(", ")));
    }

    /**
     * Round-3 diagnostic (cloud log): the TOP owner's per-cluster post weekdays + first/last timestamps and the derived
     * schedule, so we can verify the labelling + open-span (e.g. whether a given weekday is a START, an END, or
     * didn't cluster) against the real corpus without DB access.
     */
    private void logScheduleDiagnostics(UUID id, Set<String> owners, List<ScheduleDeriver.ClusterPosts> clusters,
                                        ScheduleFacet s) {
        StringBuilder cl = new StringBuilder();
        for (int i = 0; i < clusters.size(); i++) {
            List<Instant> times = clusters.get(i).posts().stream()
                    .map(ScheduleDeriver.Post::postedAt).filter(t -> t != null).sorted().toList();
            String weekdays = times.stream()
                    .map(t -> t.atZone(ZoneOffset.UTC).getDayOfWeek().toString().substring(0, 3))
                    .distinct().collect(Collectors.joining(","));
            cl.append(String.format("c%d{posts=%d,weekdays=[%s],first=%s,last=%s} ",
                    i, clusters.get(i).posts().size(), weekdays,
                    times.isEmpty() ? "-" : times.get(0), times.isEmpty() ? "-" : times.get(times.size() - 1)));
        }
        log.info("SCHEDULE_DIAG snapshot={} owners={} {} type={} start={} end={} offset={} openDays={} openConf={} "
                        + "rounds={} state={}",
                id, owners, cl.toString().trim(), s.groupType(), s.start(), s.end(), s.endMarkerDayOffset(),
                s.openWeekdays(), String.format("%.2f", s.openingDaysConfidence()), s.roundCount(), s.currentState());
    }

    /** Greedy single-link clustering of items by dHash Hamming distance, returned largest cluster first. */
    private List<Cluster> cluster(List<CorpusSnapshotItem> gridItems) {
        List<Cluster> clusters = new ArrayList<>();
        for (CorpusSnapshotItem item : gridItems) {
            String hash = item.getDHash();
            Cluster match = null;
            for (Cluster c : clusters) {
                if (c.representative().length() == hash.length()
                        && hamming(c.representative(), hash) <= hammingThreshold) {
                    match = c;
                    break;
                }
            }
            if (match == null) {
                clusters.add(new Cluster(hash, item));
            } else {
                match.add(item);
            }
        }
        clusters.sort(Comparator.comparingInt(Cluster::size).reversed());
        return clusters;
    }

    /**
     * P1.5 TIMING MERGE (vision §5.8): unify pixel clusters that a re-screenshot SPLIT — same marker, drifted dHash —
     * by their matching <strong>round-cadence time-of-day</strong> signature. A cluster participates only when it has a
     * tight, well-dated timing signature ({@link MarkerTiming#signature} over &ge; {@code minClusterSize} dated posts);
     * it then merges into the first earlier participant within {@code timingMergeToleranceMinutes} of it (largest-first,
     * so variants fold into the dominant banner) <strong>and only when their representative images are actually similar</strong>
     * (dHash distance &le; {@code timingMergeMaxHamming}). That visual gate is essential: a group that posts its START and
     * END banners TOGETHER (e.g. {@code dailyblogger___} at ~18:35) would otherwise have two DIFFERENT markers fused by
     * time alone, collapsing a TWO_MARKER group to SINGLE_MARKER. This recovers the FULL owner set + round cadence from a
     * genuinely-reunited cluster while keeping the group-type cluster count honest. A non-positive tolerance disables it;
     * a scattered or sparsely-dated cluster never merges. Undated grids (no {@code postedAt}) are unaffected.
     */
    private List<Cluster> mergeByTiming(List<Cluster> clusters) {
        if (timingMergeToleranceMinutes <= 0) {
            return clusters;
        }
        List<Cluster> result = new ArrayList<>();
        List<Cluster> anchors = new ArrayList<>();
        List<Double> anchorMeans = new ArrayList<>();
        for (Cluster c : clusters) {
            MarkerTiming.TimeSignature sig = signatureOf(c);
            if (sig == null) {
                result.add(c);
                continue;
            }
            Cluster anchor = null;
            for (int i = 0; i < anchors.size(); i++) {
                if (MarkerTiming.circularDistanceMinutes(sig.meanMinutes(), anchorMeans.get(i))
                        <= timingMergeToleranceMinutes
                        && sameBanner(anchors.get(i).representative(), c.representative())) {
                    anchor = anchors.get(i);
                    break;
                }
            }
            if (anchor == null) {
                result.add(c);
                anchors.add(c);
                anchorMeans.add(sig.meanMinutes());
            } else {
                anchor.addAll(c.members());
            }
        }
        result.sort(Comparator.comparingInt(Cluster::size).reversed());
        return result;
    }

    /** The cluster's round-cadence time-of-day signature from its dated posts, or {@code null} when it can't time-merge. */
    private MarkerTiming.TimeSignature signatureOf(Cluster c) {
        List<Instant> times = c.members().stream()
                .map(CorpusSnapshotItem::getPostedAt)
                .filter(t -> t != null)
                .toList();
        return MarkerTiming.signature(times, minClusterSize, timingMergeMinConcentration);
    }

    /**
     * True when two cluster representatives are close enough to be the SAME banner reframed (a re-screenshot variant),
     * not two DIFFERENT markers. This is the visual guard on the timing merge: it stops a group that posts its START and
     * END banners at the same time from having those distinct banners fused by time alone. Distances above the pixel
     * threshold but within {@code timingMergeMaxHamming} are a drifted variant; a clearly-different banner is further.
     */
    private boolean sameBanner(String a, String b) {
        return a.length() == b.length() && hamming(a, b) <= timingMergeMaxHamming;
    }

    private MarkerCluster toMarkerCluster(MarkerCandidate c) {
        return new MarkerCluster(c.dHash(), c.distinctPosts(), c.authors(), c.sampleShortcodes());
    }

    /** Hamming distance between two equal-length dHash strings (each char is a bit). Mirrors {@code lib/dhash.js}. */
    private static int hamming(String a, String b) {
        int d = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                d++;
            }
        }
        return d;
    }

    /** A mutable accumulator for one dHash cluster; its first member's hash is the representative. */
    private static final class Cluster {

        private final String representative;
        private final List<CorpusSnapshotItem> members = new ArrayList<>();

        private Cluster(String representative, CorpusSnapshotItem first) {
            this.representative = representative;
            this.members.add(first);
        }

        private void add(CorpusSnapshotItem item) {
            members.add(item);
        }

        private void addAll(List<CorpusSnapshotItem> more) {
            members.addAll(more);
        }

        private int size() {
            return members.size();
        }

        private String representative() {
            return representative;
        }

        private List<CorpusSnapshotItem> members() {
            return members;
        }
    }

    /**
     * The per-owner contribution breakdown for the advisory OWNER CANDIDATES table (M6): one row per owner (a repeat
     * contributor of the given marker clusters), carrying their distinct marker-post count, how many of those banners
     * they recur on, and their share of the owner set's marker posts (the duty split, 0..1). Because a post has exactly
     * one author, summing each owner's distinct marker posts equals the total, so the shares are a clean partition.
     * Ranked by post count, ties by name. Empty when no cluster is given.
     */
    private static List<OwnerCandidate> ownerBreakdown(List<MarkerCandidate> clusters) {
        Map<String, Integer> postsByOwner = new LinkedHashMap<>();
        Map<String, Integer> clustersByOwner = new LinkedHashMap<>();
        for (MarkerCandidate c : clusters) {
            c.markerPostsByAuthor().forEach((author, posts) -> {
                postsByOwner.merge(author, posts, Integer::sum);
                clustersByOwner.merge(author, 1, Integer::sum);
            });
        }
        int totalPosts = postsByOwner.values().stream().mapToInt(Integer::intValue).sum();
        // Every candidate cluster cleared repeatCoverage >= minCoverage (> 0), so a non-empty breakdown always has
        // totalPosts > 0; when there are no owners the stream is empty and no division runs.
        return postsByOwner.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(e -> new OwnerCandidate(e.getKey(), e.getValue(), clustersByOwner.get(e.getKey()),
                        (double) e.getValue() / totalPosts))
                .toList();
    }

    /**
     * A recurring-image cluster reduced to what marker-owner-SET detection needs (M6): the author who dominates it, its
     * REPEAT CONTRIBUTORS (the owner set — authors who recur >= repeatContributorMin times) and their coverage of the
     * cluster, how many DISTINCT posts (shortcodes) recur, and how pure the top author's ownership is. Counting DISTINCT
     * posts (not raw rows) means a collab post fanned into per-author rows counts once, and per-post co-authors don't
     * inflate recurrence.
     */
    private record MarkerCandidate(String dHash, int distinctPosts, String dominantAuthor, int recurrence,
                                   double purity, double cadenceRegularity, double coverage, double score,
                                   List<String> repeatContributors, double repeatCoverage,
                                   List<String> authors, List<String> sampleShortcodes,
                                   List<ScheduleDeriver.Post> markerPosts, Map<String, Integer> markerPostsByAuthor) {

        static MarkerCandidate from(String dHash, List<CorpusSnapshotItem> members, int maxSamples, int itemCount,
                                    Set<String> withImages, int repeatContributorMin) {
            Map<String, Set<String>> shortcodesByAuthor = new LinkedHashMap<>();
            Map<String, Integer> ordinalByShortcode = new LinkedHashMap<>();
            Map<String, Instant> postedAtByShortcode = new LinkedHashMap<>();
            Set<String> distinct = new LinkedHashSet<>();
            for (CorpusSnapshotItem item : members) {
                distinct.add(item.getShortcode());
                shortcodesByAuthor.computeIfAbsent(item.getAuthorUsername(), k -> new LinkedHashSet<>())
                        .add(item.getShortcode());
                ordinalByShortcode.putIfAbsent(item.getShortcode(), item.getOrdinal());
                postedAtByShortcode.putIfAbsent(item.getShortcode(), item.getPostedAt());
            }
            // Dominant author = the one contributing the most DISTINCT posts (ties broken by name for determinism).
            Map.Entry<String, Set<String>> top = shortcodesByAuthor.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Set<String>>>comparingInt(e -> e.getValue().size()).reversed()
                            .thenComparing(Map.Entry::getKey))
                    .toList().get(0);
            int distinctPosts = distinct.size();
            int recurrence = top.getValue().size();
            double purity = (double) recurrence / distinctPosts;
            // The marker-owner SET (M6, [DECIDED 5.8]): the REPEAT CONTRIBUTORS — authors who re-posted this banner across
            // >= repeatContributorMin DISTINCT posts, ranked by post count (ties by name). A genuine banner recurs because
            // its owner(s) re-post a fresh copy each round; co-owners on a duty rota BOTH recur, so BOTH are owners. A
            // lookalike flood is many members with ONE post each — no repeat contributors.
            List<String> repeatContributors = shortcodesByAuthor.entrySet().stream()
                    .filter(e -> e.getValue().size() >= repeatContributorMin)
                    .sorted(Comparator.<Map.Entry<String, Set<String>>>comparingInt(e -> e.getValue().size()).reversed()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .toList();
            // The MARKER posts are the distinct posts by any repeat contributor (the round-boundary banners). The marker
            // SIGNATURE (recurrence x cadence x coverage) + repeatCoverage are measured on THESE, so a shared-banner
            // co-owned marker scores on the FULL cadence (both owners' posts), not one owner's half. markerPostsByAuthor
            // keeps each co-owner's distinct marker-post count so the advisory can break the cluster down per owner (M6).
            LinkedHashSet<String> markerShortcodes = new LinkedHashSet<>();
            Map<String, Integer> markerPostsByAuthor = new LinkedHashMap<>();
            for (String author : repeatContributors) {
                Set<String> owned = shortcodesByAuthor.get(author);
                markerShortcodes.addAll(owned);
                markerPostsByAuthor.put(author, owned.size());
            }
            double repeatCoverage = (double) markerShortcodes.size() / distinctPosts;
            List<Integer> ordinals = markerShortcodes.stream().map(ordinalByShortcode::get).sorted().toList();
            double cadenceRegularity = cadenceRegularity(ordinals);
            double coverage = coverage(ordinals, itemCount);
            double score = markerShortcodes.size() * cadenceRegularity * coverage;
            List<String> authors = shortcodesByAuthor.keySet().stream().sorted().toList();
            // Prefer a shortcode whose representative image was actually captured, so the admin sees a thumbnail (not a
            // 404) — the captured representative of a cluster is often not its first post by grid order.
            List<String> samples = distinct.stream()
                    .sorted(Comparator.comparingInt((String sc) -> withImages.contains(sc) ? 0 : 1))
                    .limit(maxSamples)
                    .toList();
            // The marker posts (ordinal + postedAt) feed the M3b schedule derivation — the round boundaries the owner SET
            // posted (both co-owners for a shared banner).
            List<ScheduleDeriver.Post> markerPosts = markerShortcodes.stream()
                    .map(sc -> new ScheduleDeriver.Post(ordinalByShortcode.get(sc), postedAtByShortcode.get(sc)))
                    .sorted(Comparator.comparingInt(ScheduleDeriver.Post::ordinal))
                    .toList();
            return new MarkerCandidate(dHash, distinctPosts, top.getKey(), recurrence, purity, cadenceRegularity,
                    coverage, score, repeatContributors, repeatCoverage, authors, samples, markerPosts,
                    markerPostsByAuthor);
        }

        /** One recurring-image cluster as an untyped reference; confidence = normalised marker-signature strength. */
        MarkerReference toReference(double minScore) {
            return new MarkerReference(dHash, distinctPosts, dominantAuthor, sampleShortcodes,
                    Math.min(1.0, score / minScore));
        }

        /** This cluster as a broadened {@link AiMarkerSample} for the AI request — image + sorted post timestamps (M4.6). */
        AiMarkerSample toAiSample() {
            List<Instant> postedAt = markerPosts.stream()
                    .map(ScheduleDeriver.Post::postedAt)
                    .filter(t -> t != null)
                    .sorted()
                    .toList();
            return new AiMarkerSample(distinctPosts, dominantAuthor, recurrence, cadenceRegularity, coverage, score,
                    sampleShortcodes, postedAt);
        }

        /** Regularity of the gaps between successive posts (1 = perfectly even); needs >= 3 posts to mean anything. */
        private static double cadenceRegularity(List<Integer> ordinals) {
            if (ordinals.size() < 3) {
                return NEUTRAL_CADENCE;
            }
            List<Integer> gaps = new ArrayList<>();
            for (int i = 1; i < ordinals.size(); i++) {
                gaps.add(ordinals.get(i) - ordinals.get(i - 1));
            }
            double mean = gaps.stream().mapToInt(Integer::intValue).average().orElse(1.0);
            double variance = gaps.stream().mapToDouble(g -> (g - mean) * (g - mean)).average().orElse(0.0);
            return 1.0 / (1.0 + Math.sqrt(variance) / mean);
        }

        /** Fraction of the grid the cluster spans (1 = first post at the top, last at the bottom). */
        private static double coverage(List<Integer> ordinals, int itemCount) {
            if (ordinals.size() < 2 || itemCount < 2) {
                return 0.0;
            }
            return (double) (ordinals.get(ordinals.size() - 1) - ordinals.get(0)) / (itemCount - 1);
        }
    }
}
