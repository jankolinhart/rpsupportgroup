package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.corpus.CorpusSnapshotItem;
import com.reelypops.rpsupportgroup.corpus.CorpusSnapshotItemRepository;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusSnapshot;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusSnapshotRepository;
import com.reelypops.rpsupportgroup.vetting.DetectorProfileProposal.MarkerCluster;
import com.reelypops.rpsupportgroup.vetting.DetectorProfileProposal.ProposedType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tier&nbsp;0 of the P3 vetting pipeline: cheap, local, no-AI. It clusters a sealed snapshot's grid items by perceptual
 * (dHash) similarity to spot a recurring marker banner, derives the candidate owner roster + a confidence, and hands the
 * result to the {@link AiVettingEnricher} seam (a no-op by default) for optional Tier&nbsp;1/2 refinement. The output is
 * an <strong>advisory</strong> {@link DetectorProfileProposal} — it pre-fills the admin form but never auto-vets.
 */
@Service
public class VettingProposalService {

    private static final String PROVENANCE_TIER0 = "TIER_0_DHASH";
    // A banner re-posted this many DISTINCT times is fully convincing on recurrence alone; fewer scales confidence down.
    private static final int RECURRENCE_CONFIDENCE_TARGET = 3;

    private final MarkerCorpusSnapshotRepository snapshots;
    private final CorpusSnapshotItemRepository items;
    private final AiVettingEnricher enricher;
    private final int hammingThreshold;
    private final int minClusterSize;
    private final int maxClusters;
    private final int maxSamples;
    private final double minPurity;

    public VettingProposalService(MarkerCorpusSnapshotRepository snapshots, CorpusSnapshotItemRepository items,
                                  AiVettingEnricher enricher,
                                  @Value("${rp.vetting.dhash-threshold:4}") int hammingThreshold,
                                  @Value("${rp.vetting.min-cluster-size:2}") int minClusterSize,
                                  @Value("${rp.vetting.max-clusters:5}") int maxClusters,
                                  @Value("${rp.vetting.max-samples:5}") int maxSamples,
                                  @Value("${rp.vetting.min-purity:0.75}") double minPurity) {
        this.snapshots = snapshots;
        this.items = items;
        this.enricher = enricher;
        this.hammingThreshold = hammingThreshold;
        this.minClusterSize = minClusterSize;
        this.maxClusters = maxClusters;
        this.maxSamples = maxSamples;
        this.minPurity = minPurity;
    }

    /** Build the advisory vetting proposal for a snapshot (404 if unknown), then run it through the enricher seam. */
    @Transactional(readOnly = true)
    public DetectorProfileProposal propose(UUID snapshotId) {
        MarkerCorpusSnapshot snapshot = snapshots.findById(snapshotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no snapshot " + snapshotId));
        List<CorpusSnapshotItem> gridItems = items.findBySnapshotIdOrderByOrdinalAsc(snapshotId);
        return enricher.enrich(buildTier0(snapshot, gridItems), gridItems);
    }

    private DetectorProfileProposal buildTier0(MarkerCorpusSnapshot snapshot, List<CorpusSnapshotItem> gridItems) {
        UUID id = snapshot.getId();
        String ig = snapshot.getIgAccount();
        int itemCount = gridItems.size();
        if (itemCount == 0) {
            return new DetectorProfileProposal(id, ig, 0, ProposedType.UNKNOWN, List.of(), List.of(),
                    0.0, false, PROVENANCE_TIER0);
        }
        List<Cluster> strong = cluster(gridItems).stream().filter(c -> c.size() >= minClusterSize).toList();
        if (strong.isEmpty()) {
            // No image recurs across the grid: a text-overlay style — Tier 0 cannot judge it, so escalate to vision.
            return new DetectorProfileProposal(id, ig, itemCount, ProposedType.TEXT_OVERLAY, List.of(), List.of(),
                    0.0, true, PROVENANCE_TIER0);
        }
        // A genuine flat-banner marker is the SAME image re-posted across DISTINCT posts by a SINGLE owner (directive
        // P5: a post appears in the grid only once, so an image recurs only because the owner re-posts a fresh banner
        // each round). Keep only clusters that (a) recur across >= minClusterSize DISTINCT posts and (b) are dominated
        // by one author (purity >= minPurity). This rejects multi-author lookalike clusters (different members posting
        // visually-similar photos) and single collab posts fanned into per-author rows — the two things that flooded
        // the roster with non-owners on large grids.
        List<MarkerCandidate> candidates = strong.stream()
                .map(c -> MarkerCandidate.from(c.representative(), c.members(), maxSamples))
                .filter(c -> c.distinctPosts() >= minClusterSize && c.purity() >= minPurity)
                .sorted(Comparator.comparingInt(MarkerCandidate::recurrence).reversed()
                        .thenComparing(MarkerCandidate::dominantAuthor))
                .toList();
        if (candidates.isEmpty()) {
            // Images recur, but none is a clean single-owner banner (multi-author lookalikes / collab fan-out) — punt
            // to vision rather than proposing a wrong owner.
            return new DetectorProfileProposal(id, ig, itemCount, ProposedType.TEXT_OVERLAY, List.of(), List.of(),
                    0.0, true, PROVENANCE_TIER0);
        }
        List<MarkerCluster> clusters = candidates.stream().limit(maxClusters).map(this::toMarkerCluster).toList();
        // Roster = the dominant author of each candidate cluster, strongest first — NOT the union of every recurring
        // cluster's authors. A single owner who posts two banners (e.g. an END + START pair each round) collapses to
        // one entry via distinct().
        List<String> ownerRoster = candidates.stream().map(MarkerCandidate::dominantAuthor).distinct().toList();
        double confidence = confidenceOf(candidates.get(0));
        return new DetectorProfileProposal(id, ig, itemCount, ProposedType.FLAT_BANNER, ownerRoster, clusters,
                confidence, false, PROVENANCE_TIER0);
    }

    /** Confidence in the top candidate: its single-author purity, tempered by how many DISTINCT times it recurs. */
    private double confidenceOf(MarkerCandidate top) {
        return top.purity() * Math.min(1.0, (double) top.recurrence() / RECURRENCE_CONFIDENCE_TARGET);
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
                                   double purity, List<String> authors, List<String> sampleShortcodes) {

        static MarkerCandidate from(String dHash, List<CorpusSnapshotItem> members, int maxSamples) {
            Map<String, Set<String>> shortcodesByAuthor = new LinkedHashMap<>();
            Set<String> distinct = new LinkedHashSet<>();
            for (CorpusSnapshotItem item : members) {
                distinct.add(item.getShortcode());
                shortcodesByAuthor.computeIfAbsent(item.getAuthorUsername(), k -> new LinkedHashSet<>())
                        .add(item.getShortcode());
            }
            // Dominant author = the one contributing the most DISTINCT posts (ties broken by name for determinism).
            Map.Entry<String, Set<String>> top = shortcodesByAuthor.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Set<String>>>comparingInt(e -> e.getValue().size()).reversed()
                            .thenComparing(Map.Entry::getKey))
                    .toList().get(0);
            int distinctPosts = distinct.size();
            int recurrence = top.getValue().size();
            double purity = (double) recurrence / distinctPosts;
            List<String> authors = shortcodesByAuthor.keySet().stream().sorted().toList();
            List<String> samples = distinct.stream().limit(maxSamples).toList();
            return new MarkerCandidate(dHash, distinctPosts, top.getKey(), recurrence, purity, authors, samples);
        }
    }
}
