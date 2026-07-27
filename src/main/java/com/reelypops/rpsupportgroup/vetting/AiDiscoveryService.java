package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient;
import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient.VettingRequest;
import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient.VettingResponse;
import com.reelypops.rpsupportgroup.corpus.CorpusRepresentative;
import com.reelypops.rpsupportgroup.corpus.MarkerCorpusService;
import com.reelypops.rpsupportgroup.group.DetectedProfile;
import com.reelypops.rpsupportgroup.group.DetectedProfile.AiDiscovery;
import com.reelypops.rpsupportgroup.group.DetectedProfile.AiDiscovery.WeeklySchedule;
import com.reelypops.rpsupportgroup.group.MarkerStyle;
import com.reelypops.rpsupportgroup.group.SupportGroupConfig;
import com.reelypops.rpsupportgroup.group.SupportGroupConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The explicit AI-discovery action (marker-auto-discovery M4): the admin's "Run AI discovery" triggers this to enrich a
 * snapshot's advisory with the {@link AiDiscovery} facet — the judgment Tier&nbsp;0 cannot make for a text-overlay group.
 * It runs the Tier-0 {@link DetectedProfileService#detect detect} first (so the base advisory is fresh), reduces the
 * clusters + representative images to a request, calls {@link RpAiGatewayClient}, and stores the verdict. It is
 * <strong>fail-open</strong>: when the gateway is disabled or unavailable the base (Tier-0) advisory is returned
 * unchanged, so the admin still vets by hand. The AI is <em>never</em> run on the ordinary {@code detect} path.
 */
@Service
public class AiDiscoveryService {

    // M4.5 v1: marker-post occurrence times are sent to the AI in UTC (the same frame the derived schedule uses until
    // the admin sets the group timezone in the Vetting Portal); the per-weekday verdict comes back in the same frame.
    private static final String TIMEZONE = "UTC";

    private final DetectedProfileService detected;
    private final VettingProposalService proposals;
    private final MarkerCorpusService corpus;
    private final RpAiGatewayClient gateway;
    private final SupportGroupConfigRepository configs;
    private final int maxImages;
    private final int perClusterSamples;
    private static final Logger log = LoggerFactory.getLogger(AiDiscoveryService.class);

    public AiDiscoveryService(DetectedProfileService detected, VettingProposalService proposals,
                              MarkerCorpusService corpus, RpAiGatewayClient gateway,
                              SupportGroupConfigRepository configs,
                              @Value("${rp.aigateway.max-images:24}") int maxImages,
                              @Value("${rp.aigateway.samples-per-cluster:3}") int perClusterSamples) {
        this.detected = detected;
        this.proposals = proposals;
        this.corpus = corpus;
        this.gateway = gateway;
        this.configs = configs;
        this.maxImages = Math.max(1, maxImages);
        this.perClusterSamples = Math.max(1, perClusterSamples);
    }

    /**
     * Refresh the Tier-0 advisory, then (if the gateway is configured + reachable) attach the AI verdict and persist it.
     * 404 if the snapshot or its config is unknown; the base advisory is returned unchanged on any AI failure.
     */
    @Transactional
    public DetectedProfile runAiDiscovery(UUID snapshotId) {
        DetectedProfile base = detected.detect(snapshotId);
        AiRequest aiRequest = buildRequest(snapshotId, base);
        Optional<VettingResponse> verdict = gateway.vet(aiRequest.request());
        if (verdict.isEmpty()) {
            return base;
        }
        DetectedProfile enriched = withAi(base, toDiscovery(verdict.get(), aiRequest.clusterShortcodes()));
        SupportGroupConfig config = configs.findByIgAccount(base.igAccount())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no config for " + base.igAccount()));
        config.updateDetectedProfile(enriched);
        configs.save(config);
        return enriched;
    }

    /** Reduce the broadened per-owner marker sample (+ representative images) to the gateway request; fall back to raw representatives. */
    private AiRequest buildRequest(UUID snapshotId, DetectedProfile base) {
        SnapshotAnalysis analysis = proposals.analyze(snapshotId);
        List<AiMarkerSample> samples = analysis.aiSamples();
        List<VettingRequest.Cluster> clusters = new ArrayList<>();
        List<String> clusterShortcodes = new ArrayList<>();
        // M4.10: send ONE representative image per owner cluster FIRST, THEN deepen up to perClusterSamples (round-robin
        // by depth), so every distinct cluster reaches the model before any single cluster claims a second slot under the
        // maxImages cap — a low-recurrence weekend variant (e.g. a "START Sonntag" that dHash-shattered into its own
        // sub-threshold singleton) is a WHOLE cluster and must not be starved by a high-recurrence cluster's extra samples.
        // A cluster with no captured image contributes one image-less entry so its occurrence timings still inform the schedule.
        int coverable = Math.min(samples.size(), maxImages);
        List<List<CapturedImage>> imagesPerCluster = new ArrayList<>();
        for (int i = 0; i < coverable; i++) {
            imagesPerCluster.add(capturedImages(snapshotId, samples.get(i)));
        }
        for (int depth = 0; depth < perClusterSamples && clusters.size() < maxImages; depth++) {
            for (int i = 0; i < coverable && clusters.size() < maxImages; i++) {
                List<CapturedImage> images = imagesPerCluster.get(i);
                if (depth < images.size()) {
                    clusters.add(sampleCluster(samples.get(i), images.get(depth).dataUrl()));
                    clusterShortcodes.add(images.get(depth).shortcode());
                } else if (depth == 0) {
                    clusters.add(sampleCluster(samples.get(i), null));
                    clusterShortcodes.add(representativeShortcode(samples.get(i).sampleShortcodes()));
                }
            }
        }
        if (clusters.isEmpty()) {
            for (String shortcode : corpus.detail(snapshotId).representativeShortcodes()) {
                if (clusters.size() >= maxImages) {
                    break;
                }
                String image = dataUrl(snapshotId, List.of(shortcode));
                if (image != null) {
                    clusters.add(new VettingRequest.Cluster(1, List.of(), 1, 0.0, 0.0, 0.0, image, List.of()));
                    clusterShortcodes.add(shortcode);
                }
            }
        }
        VettingRequest request = new VettingRequest(base.igAccount(), base.itemCount(), base.imageStyle().value().name(),
                base.owner().roster(), clusters, TIMEZONE, gridWindow(analysis.gridWindow()));
        log.info("AI_DISCOVERY_DIAG ig={} ownerClusters={} entriesSent={} withImage={}",
                base.igAccount(), samples.size(), clusters.size(),
                clusters.stream().filter(c -> c.imageUrl() != null).count());
        return new AiRequest(request, clusterShortcodes);
    }

    /**
     * The built gateway request paired with each cluster's representative shortcode (in cluster order), so the AI's
     * cited {@code clusterIndex} resolves back to the marker variation's image (A2/B7b).
     */
    private record AiRequest(VettingRequest request, List<String> clusterShortcodes) {
    }

    /** A cluster's representative shortcode — its image-backed first sample (A2/B7b); null when the cluster has none. */
    private static String representativeShortcode(List<String> sampleShortcodes) {
        return sampleShortcodes.isEmpty() ? null : sampleShortcodes.get(0);
    }

    /** One request cluster carrying this owner sample's Tier-0 metrics + the given (possibly null) representative image. */
    private static VettingRequest.Cluster sampleCluster(AiMarkerSample s, String image) {
        return new VettingRequest.Cluster(s.distinctPosts(), List.of(s.author()), s.recurrence(),
                s.cadenceRegularity(), s.coverage(), s.score(), image, occurrences(s.postedAt()));
    }

    /** This owner cluster's captured representative images ({@code data:} URLs) paired with their shortcodes — image-first order, capped at perClusterSamples. */
    private List<CapturedImage> capturedImages(UUID snapshotId, AiMarkerSample sample) {
        List<CapturedImage> images = new ArrayList<>();
        for (String shortcode : sample.sampleShortcodes()) {
            if (images.size() >= perClusterSamples) {
                break;
            }
            Optional<CorpusRepresentative> rep = corpus.getRepresentative(snapshotId, shortcode);
            if (rep.isPresent()) {
                images.add(new CapturedImage(shortcode, dataUrl(rep.get())));
            }
        }
        return images;
    }

    /** A captured representative image ({@code data:} URL) paired with the shortcode of the post it came from. */
    private record CapturedImage(String shortcode, String dataUrl) {
    }

    /** Map the analysis grid window (M4.7) onto the request contract — the ordered timeline for round reconstruction. */
    private static List<VettingRequest.GridRow> gridWindow(List<GridRow> window) {
        return window.stream()
                .map(r -> new VettingRequest.GridRow(r.ordinal(), r.author(), r.marker()))
                .toList();
    }

    /** The first captured representative among the shortcodes, as a base64 {@code data:} URL; null when none captured. */
    private String dataUrl(UUID snapshotId, List<String> shortcodes) {
        for (String shortcode : shortcodes) {
            Optional<CorpusRepresentative> rep = corpus.getRepresentative(snapshotId, shortcode);
            if (rep.isPresent()) {
                return dataUrl(rep.get());
            }
        }
        return null;
    }

    /** A captured representative as a base64 {@code data:} URL. */
    private static String dataUrl(CorpusRepresentative r) {
        return "data:" + r.getContentType() + ";base64," + Base64.getEncoder().encodeToString(r.getImage());
    }

    /**
     * A reference cluster's marker-post timings as {@code (weekday, HH:mm)} occurrences in {@link #TIMEZONE} (UTC in v1)
     * — the per-weekday signal the AI buckets to derive the schedule. Empty when the cluster has no captured timings.
     */
    private static List<VettingRequest.Occurrence> occurrences(List<Instant> postedAt) {
        List<VettingRequest.Occurrence> occ = new ArrayList<>();
        for (Instant t : postedAt) {
            ZonedDateTime z = t.atZone(ZoneOffset.UTC);
            occ.add(new VettingRequest.Occurrence(
                    z.getDayOfWeek().name().substring(0, 3),
                    String.format("%02d:%02d", z.getHour(), z.getMinute())));
        }
        return occ;
    }

    /** Map the gateway verdict onto the persisted {@link AiDiscovery} facet, resolving each cited clusterIndex to its image. */
    private static AiDiscovery toDiscovery(VettingResponse v, List<String> clusterShortcodes) {
        List<AiDiscovery.AiReference> references = new ArrayList<>();
        if (v.references() != null) {
            for (VettingResponse.Reference r : v.references()) {
                references.add(toAiReference(r, clusterShortcodes));
            }
        }
        return new AiDiscovery(toStyle(v.style()), v.markerType(), v.owner(), references,
                v.ocrTargetText(), v.confidence(), v.reasoning(), System.currentTimeMillis(),
                toWeeklySchedule(v.schedule(), clusterShortcodes));
    }

    /** Map the gateway's per-weekday verdict onto the persisted {@link WeeklySchedule}; null when the model returned none. */
    private static WeeklySchedule toWeeklySchedule(List<VettingResponse.DaySchedule> days, List<String> clusterShortcodes) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        List<WeeklySchedule.DaySchedule> mapped = new ArrayList<>();
        for (VettingResponse.DaySchedule d : days) {
            List<AiDiscovery.AiReference> markers = new ArrayList<>();
            if (d.markers() != null) {
                for (VettingResponse.Reference m : d.markers()) {
                    markers.add(toAiReference(m, clusterShortcodes));
                }
            }
            mapped.add(new WeeklySchedule.DaySchedule(d.weekday(), d.open(), d.groupType(), d.style(), markers,
                    d.start(), d.end(), d.endMarkerDayOffset(), d.maxTaggedPosts(), d.confidence()));
        }
        return new WeeklySchedule(TIMEZONE, mapped);
    }

    /** One AI reference with the representative shortcode of its cited cluster resolved (A2/B7b). */
    private static AiDiscovery.AiReference toAiReference(VettingResponse.Reference r, List<String> clusterShortcodes) {
        return new AiDiscovery.AiReference(r.markerType(), r.ocrText(), shortcodeFor(r.clusterIndex(), clusterShortcodes));
    }

    /** Resolve the AI's 1-based clusterIndex to the cluster's representative shortcode; null when unset / out of range. */
    private static String shortcodeFor(Integer clusterIndex, List<String> clusterShortcodes) {
        if (clusterIndex == null || clusterIndex < 1 || clusterIndex > clusterShortcodes.size()) {
            return null;
        }
        return clusterShortcodes.get(clusterIndex - 1);
    }

    private static MarkerStyle toStyle(String style) {
        if ("FLAT_BANNER".equals(style)) {
            return MarkerStyle.FLAT_BANNER;
        }
        if ("TEXT_OVERLAY".equals(style)) {
            return MarkerStyle.TEXT_OVERLAY;
        }
        return MarkerStyle.UNKNOWN;
    }

    /** A copy of the Tier-0 advisory with the AI facet attached. */
    private static DetectedProfile withAi(DetectedProfile base, AiDiscovery discovery) {
        return new DetectedProfile(base.snapshotId(), base.igAccount(), base.itemCount(), base.generatedAtMs(),
                base.provenance(), base.escalate(), base.imageStyle(), base.owner(), base.references(),
                base.candidates(), base.schedule(), discovery);
    }
}
