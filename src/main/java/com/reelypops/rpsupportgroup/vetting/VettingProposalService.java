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
import java.util.List;
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

    private final MarkerCorpusSnapshotRepository snapshots;
    private final CorpusSnapshotItemRepository items;
    private final AiVettingEnricher enricher;
    private final int hammingThreshold;
    private final int minClusterSize;
    private final int maxClusters;
    private final int maxSamples;

    public VettingProposalService(MarkerCorpusSnapshotRepository snapshots, CorpusSnapshotItemRepository items,
                                  AiVettingEnricher enricher,
                                  @Value("${rp.vetting.dhash-threshold:8}") int hammingThreshold,
                                  @Value("${rp.vetting.min-cluster-size:2}") int minClusterSize,
                                  @Value("${rp.vetting.max-clusters:5}") int maxClusters,
                                  @Value("${rp.vetting.max-samples:5}") int maxSamples) {
        this.snapshots = snapshots;
        this.items = items;
        this.enricher = enricher;
        this.hammingThreshold = hammingThreshold;
        this.minClusterSize = minClusterSize;
        this.maxClusters = maxClusters;
        this.maxSamples = maxSamples;
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
        List<MarkerCluster> clusters = strong.stream().limit(maxClusters).map(this::toMarkerCluster).toList();
        List<String> ownerRoster = strong.stream()
                .flatMap(c -> c.authors().stream()).distinct().sorted().toList();
        double confidence = (double) strong.get(0).size() / itemCount;
        return new DetectorProfileProposal(id, ig, itemCount, ProposedType.FLAT_BANNER, ownerRoster, clusters,
                confidence, false, PROVENANCE_TIER0);
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

    private MarkerCluster toMarkerCluster(Cluster c) {
        return new MarkerCluster(c.representative(), c.size(), c.authors(), c.sampleShortcodes(maxSamples));
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

        private List<String> authors() {
            return members.stream().map(CorpusSnapshotItem::getAuthorUsername).distinct().sorted().toList();
        }

        private List<String> sampleShortcodes(int cap) {
            return members.stream().map(CorpusSnapshotItem::getShortcode).limit(cap).toList();
        }
    }
}
