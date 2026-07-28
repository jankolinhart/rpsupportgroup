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
    private final double minPurity;
    private final double minScore;
    private final double minSeparation;

    public VettingProposalService(MarkerCorpusSnapshotRepository snapshots, CorpusSnapshotItemRepository items,
                                  CorpusRepresentativeRepository representatives,
                                  AiVettingEnricher enricher,
                                  @Value("${rp.vetting.dhash-threshold:4}") int hammingThreshold,
                                  @Value("${rp.vetting.min-cluster-size:2}") int minClusterSize,
                                  @Value("${rp.vetting.max-clusters:5}") int maxClusters,
                                  @Value("${rp.vetting.max-samples:5}") int maxSamples,
                                  @Value("${rp.vetting.min-purity:0.75}") double minPurity,
                                  @Value("${rp.vetting.min-score:2.0}") double minScore,
                                  @Value("${rp.vetting.min-separation:0.5}") double minSeparation) {
        this.snapshots = snapshots;
        this.items = items;
        this.representatives = representatives;
        this.enricher = enricher;
        this.hammingThreshold = hammingThreshold;
        this.minClusterSize = minClusterSize;
        this.maxClusters = maxClusters;
        this.maxSamples = maxSamples;
        this.minPurity = minPurity;
        this.minScore = minScore;
        this.minSeparation = minSeparation;
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
        List<Cluster> allClusters = cluster(gridItems);
        List<Cluster> strong = allClusters.stream().filter(c -> c.size() >= minClusterSize).toList();
        if (strong.isEmpty()) {
            // No image recurs across the grid: a text-overlay style — Tier 0 cannot judge it, so escalate to vision.
            return emptyAnalysis(new DetectorProfileProposal(id, ig, itemCount, ProposedType.TEXT_OVERLAY, List.of(),
                    List.of(), 0.0, true, PROVENANCE_TIER0));
        }
        // A genuine flat-banner marker is the SAME image re-posted across DISTINCT posts by a SINGLE owner (directive
        // P5: a post appears in the grid only once, so an image recurs only because the owner re-posts a fresh banner
        // each round). Keep only clusters that (a) recur across >= minClusterSize DISTINCT posts and (b) are dominated
        // by one author (purity >= minPurity). This rejects multi-author lookalike clusters (different members posting
        // visually-similar photos) and single collab posts fanned into per-author rows — the two things that flooded
        // the roster with non-owners on large grids.
        // Each surviving cluster is scored on its marker SIGNATURE (M2, "Fast M2"): recurrence x cadence-regularity x
        // coverage. A real marker recurs at a REGULAR cadence spanning the window; a serial re-poster does not.
        List<MarkerCandidate> candidates = strong.stream()
                .map(c -> MarkerCandidate.from(c.representative(), c.members(), maxSamples, itemCount, withImages))
                .filter(c -> c.distinctPosts() >= minClusterSize && c.purity() >= minPurity)
                .sorted(Comparator.comparingDouble(MarkerCandidate::score).reversed()
                        .thenComparing(MarkerCandidate::dominantAuthor))
                .toList();
        if (candidates.isEmpty()) {
            // Images recur, but none is a clean single-owner banner (multi-author lookalikes / collab fan-out) — punt
            // to vision rather than proposing a wrong owner.
            return emptyAnalysis(new DetectorProfileProposal(id, ig, itemCount, ProposedType.TEXT_OVERLAY, List.of(),
                    List.of(), 0.0, true, PROVENANCE_TIER0));
        }
        // The gate. confidence = how strongly the top OWNER stands out from the next DIFFERENT owner:
        // min(separation, absolute-strength). Accept ONE owner only on a SLAM DUNK — a strong score AND a clear lead —
        // else ESCALATE (bias to escalate; the AI is effectively free). A slam-dunk names the owner alone; an ambiguous
        // grid keeps the ranked roster as context but flags escalate (which of these is the owner? Tier 0 won't guess).
        // Separation is measured OWNER-to-owner, not cluster-to-cluster (M2.1): a 2-marker owner legitimately produces
        // TWO clusters (START + END), so its own second banner must NOT count as a rival — else the owner ties with
        // itself and a clean single owner escalates for no reason. We compare the top cluster against the best cluster
        // of the next DIFFERENT author (0 when the owner has the field to itself).
        MarkerCandidate top = candidates.get(0);
        double secondScore = candidates.stream()
                .filter(c -> !c.dominantAuthor().equals(top.dominantAuthor()))
                .mapToDouble(MarkerCandidate::score)
                .max()
                .orElse(0.0);
        double separation = (top.score() - secondScore) / top.score();
        double strength = Math.min(1.0, top.score() / minScore);
        double confidence = Math.min(separation, strength);
        boolean slamDunk = strength >= 1.0 && separation >= minSeparation;
        logDiagnostics(id, itemCount, candidates, slamDunk, confidence);

        List<MarkerCluster> clusters = candidates.stream().limit(maxClusters).map(this::toMarkerCluster).toList();
        DetectorProfileProposal proposal;
        if (slamDunk) {
            proposal = new DetectorProfileProposal(id, ig, itemCount, ProposedType.FLAT_BANNER,
                    List.of(top.dominantAuthor()), clusters, confidence, false, PROVENANCE_TIER0);
        } else {
            List<String> roster = candidates.stream().map(MarkerCandidate::dominantAuthor).distinct().toList();
            proposal = new DetectorProfileProposal(id, ig, itemCount, ProposedType.FLAT_BANNER, roster, clusters,
                    confidence, true, PROVENANCE_TIER0);
        }
        // M3b: surface the ranked candidate metrics + per-reference confidence, and derive the schedule from the TOP
        // owner's clusters (its START/END banners) — advisory, whether we accepted or escalated.
        List<OwnerCandidate> ownerCandidates = candidates.stream().map(MarkerCandidate::toOwnerCandidate).toList();
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
        // per-weekday occurrence source (bucketed by weekday + time in AiDiscoveryService).
        List<List<Instant>> referencePostedAt = referenceCandidates.stream()
                .map(c -> c.ownerPosts().stream()
                        .map(ScheduleDeriver.Post::postedAt)
                        .filter(t -> t != null)
                        .sorted()
                        .toList())
                .toList();
        List<ScheduleDeriver.ClusterPosts> ownerClusters = candidates.stream()
                .filter(c -> c.dominantAuthor().equals(top.dominantAuthor()))
                .map(c -> new ScheduleDeriver.ClusterPosts(c.ownerPosts()))
                .toList();
        // The FULL ordered grid (one row per post × author, directive P4) — not the capped AI window — so the
        // deterministic maxTaggedPosts count spans every round the corpus captured (vision §5.6).
        List<GridRow> allRows = gridItems.stream()
                .map(it -> new GridRow(it.getOrdinal(), it.getAuthorUsername(),
                        top.dominantAuthor().equals(it.getAuthorUsername())))
                .toList();
        ScheduleFacet schedule = ScheduleDeriver.derive(ownerClusters, allRows);
        logScheduleDiagnostics(id, top.dominantAuthor(), ownerClusters, schedule);
        // M4.6/M4.10: the broadened marker sample for the AI — EVERY captured cluster of the proposed owner, not only the
        // score>=minScore references and not only the size>=minClusterSize candidates. A high-variation banner shatters two
        // ways: into sub-SCORE fragments (M4.6) AND into sub-SIZE dHash SINGLETONS — a weekend-only "START Sonntag" posted
        // once or twice over changing backgrounds never clusters, so the `strong` gate drops it and it never reaches the
        // model even though it is a preselected marker candidate the admin SEES in the corpus grid. Rebuild from ALL
        // clusters and keep every owner-dominant one that is EITHER a scored candidate (size>=minClusterSize, single-owner,
        // as before) OR carries a captured representative image (the dropped singleton variants); an image-less owner
        // singleton stays out (pure noise). AiDiscoveryService round-robins one image per cluster first, so the extra
        // low-score singletons here are not starved by the maxImages cap.
        List<AiMarkerSample> aiSamples = allClusters.stream()
                .map(c -> MarkerCandidate.from(c.representative(), c.members(), maxSamples, itemCount, withImages))
                .filter(c -> c.dominantAuthor().equals(top.dominantAuthor()))
                .filter(c -> (c.distinctPosts() >= minClusterSize && c.purity() >= minPurity)
                        || c.sampleShortcodes().stream().anyMatch(withImages::contains))
                .sorted(Comparator.comparingDouble(MarkerCandidate::score).reversed()
                        .thenComparing(MarkerCandidate::dominantAuthor))
                .map(MarkerCandidate::toAiSample)
                .toList();
        // M4.7: the recent tagged-grid window in true order — the owner's markers interleaved with member posts — so the
        // AI can reconstruct rounds from the sequence (START→ENDE pairs) and count per-round members (maxTaggedPosts).
        List<GridRow> gridWindow = buildGridWindow(gridItems, top.dominantAuthor());
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
     * (post × author) row is flagged {@code marker} when its author is the proposed owner. The window is ROUND-COMPLETE
     * — it only ends on a marker (a round boundary), never mid-round, so the oldest round's START is never truncated
     * below the edge (which would leave that round with an END but no START — the missing-Sunday-START symptom). It
     * spans up to {@link #GRID_WINDOW_MARKERS} owner markers; a pathologically member-heavy grid trips
     * {@link #GRID_WINDOW_MAX_ROWS} but still extends to the next marker to close the current round.
     */
    private static List<GridRow> buildGridWindow(List<CorpusSnapshotItem> gridItems, String owner) {
        List<GridRow> rows = new ArrayList<>();
        int markers = 0;
        boolean complete = false;
        for (int i = 0; i < gridItems.size() && !complete; i++) {
            CorpusSnapshotItem item = gridItems.get(i);
            boolean marker = owner.equals(item.getAuthorUsername());
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
    private void logDiagnostics(UUID id, int itemCount, List<MarkerCandidate> candidates, boolean slamDunk,
                                double confidence) {
        log.info("VETTING_DIAG snapshot={} items={} decision={} confidence={} candidates=[{}]",
                id, itemCount, slamDunk ? "ACCEPT:" + candidates.get(0).dominantAuthor() : "ESCALATE",
                String.format("%.2f", confidence),
                candidates.stream().map(c -> String.format("%s{rec=%d,cadence=%.2f,coverage=%.2f,score=%.2f}",
                        c.dominantAuthor(), c.recurrence(), c.cadenceRegularity(), c.coverage(), c.score()))
                        .collect(Collectors.joining(", ")));
    }

    /**
     * Round-3 diagnostic (cloud log): the TOP owner's per-cluster post weekdays + first/last timestamps and the derived
     * schedule, so we can verify the labelling + open-span (e.g. whether a given weekday is a START, an END, or
     * didn't cluster) against the real corpus without DB access.
     */
    private void logScheduleDiagnostics(UUID id, String owner, List<ScheduleDeriver.ClusterPosts> clusters,
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
        log.info("SCHEDULE_DIAG snapshot={} owner={} {} type={} start={} end={} offset={} openDays={} openConf={} "
                        + "rounds={} state={}",
                id, owner, cl.toString().trim(), s.groupType(), s.start(), s.end(), s.endMarkerDayOffset(),
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
     * A single-owner recurring-image cluster reduced to what marker-owner detection needs: the author who dominates it,
     * how many DISTINCT posts (shortcodes) recur, and how pure that ownership is. Counting DISTINCT posts (not raw rows)
     * means a collab post fanned into per-author rows counts once, and per-post co-authors don't inflate recurrence.
     */
    private record MarkerCandidate(String dHash, int distinctPosts, String dominantAuthor, int recurrence,
                                   double purity, double cadenceRegularity, double coverage, double score,
                                   List<String> authors, List<String> sampleShortcodes,
                                   List<ScheduleDeriver.Post> ownerPosts) {

        static MarkerCandidate from(String dHash, List<CorpusSnapshotItem> members, int maxSamples, int itemCount,
                                    Set<String> withImages) {
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
            // Cadence + coverage are measured on the DOMINANT author's post positions (the marker recurrences).
            List<Integer> ordinals = top.getValue().stream().map(ordinalByShortcode::get).sorted().toList();
            double cadenceRegularity = cadenceRegularity(ordinals);
            double coverage = coverage(ordinals, itemCount);
            double score = recurrence * cadenceRegularity * coverage;
            List<String> authors = shortcodesByAuthor.keySet().stream().sorted().toList();
            // Prefer a shortcode whose representative image was actually captured, so the admin sees a thumbnail (not a
            // 404) — the captured representative of a cluster is often not its first post by grid order.
            List<String> samples = distinct.stream()
                    .sorted(Comparator.comparingInt((String sc) -> withImages.contains(sc) ? 0 : 1))
                    .limit(maxSamples)
                    .toList();
            // The dominant author's posts (ordinal + postedAt) feed the M3b schedule derivation.
            List<ScheduleDeriver.Post> ownerPosts = top.getValue().stream()
                    .map(sc -> new ScheduleDeriver.Post(ordinalByShortcode.get(sc), postedAtByShortcode.get(sc)))
                    .sorted(Comparator.comparingInt(ScheduleDeriver.Post::ordinal))
                    .toList();
            return new MarkerCandidate(dHash, distinctPosts, top.getKey(), recurrence, purity, cadenceRegularity,
                    coverage, score, authors, samples, ownerPosts);
        }

        /** The marker-signature metrics behind the gate, surfaced for the admin (Advisory Store, M3b). */
        OwnerCandidate toOwnerCandidate() {
            return new OwnerCandidate(dominantAuthor, distinctPosts, recurrence, purity, cadenceRegularity,
                    coverage, score);
        }

        /** One recurring-image cluster as an untyped reference; confidence = normalised marker-signature strength. */
        MarkerReference toReference(double minScore) {
            return new MarkerReference(dHash, distinctPosts, sampleShortcodes, Math.min(1.0, score / minScore));
        }

        /** This cluster as a broadened {@link AiMarkerSample} for the AI request — image + sorted post timestamps (M4.6). */
        AiMarkerSample toAiSample() {
            List<Instant> postedAt = ownerPosts.stream()
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
