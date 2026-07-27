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

    public AiDiscoveryService(DetectedProfileService detected, VettingProposalService proposals,
                              MarkerCorpusService corpus, RpAiGatewayClient gateway,
                              SupportGroupConfigRepository configs,
                              @Value("${rp.aigateway.max-images:24}") int maxImages) {
        this.detected = detected;
        this.proposals = proposals;
        this.corpus = corpus;
        this.gateway = gateway;
        this.configs = configs;
        this.maxImages = Math.max(1, maxImages);
    }

    /**
     * Refresh the Tier-0 advisory, then (if the gateway is configured + reachable) attach the AI verdict and persist it.
     * 404 if the snapshot or its config is unknown; the base advisory is returned unchanged on any AI failure.
     */
    @Transactional
    public DetectedProfile runAiDiscovery(UUID snapshotId) {
        DetectedProfile base = detected.detect(snapshotId);
        Optional<VettingResponse> verdict = gateway.vet(buildRequest(snapshotId, base));
        if (verdict.isEmpty()) {
            return base;
        }
        DetectedProfile enriched = withAi(base, toDiscovery(verdict.get()));
        SupportGroupConfig config = configs.findByIgAccount(base.igAccount())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no config for " + base.igAccount()));
        config.updateDetectedProfile(enriched);
        configs.save(config);
        return enriched;
    }

    /** Reduce the broadened per-owner marker sample (+ representative images) to the gateway request; fall back to raw representatives. */
    private VettingRequest buildRequest(UUID snapshotId, DetectedProfile base) {
        SnapshotAnalysis analysis = proposals.analyze(snapshotId);
        List<AiMarkerSample> samples = analysis.aiSamples();
        List<VettingRequest.Cluster> clusters = new ArrayList<>();
        for (int i = 0; i < samples.size() && clusters.size() < maxImages; i++) {
            AiMarkerSample s = samples.get(i);
            clusters.add(new VettingRequest.Cluster(
                    s.distinctPosts(),
                    List.of(s.author()),
                    s.recurrence(),
                    s.cadenceRegularity(),
                    s.coverage(),
                    s.score(),
                    dataUrl(snapshotId, s.sampleShortcodes()),
                    occurrences(s.postedAt())));
        }
        if (clusters.isEmpty()) {
            for (String shortcode : corpus.detail(snapshotId).representativeShortcodes()) {
                if (clusters.size() >= maxImages) {
                    break;
                }
                String image = dataUrl(snapshotId, List.of(shortcode));
                if (image != null) {
                    clusters.add(new VettingRequest.Cluster(1, List.of(), 1, 0.0, 0.0, 0.0, image, List.of()));
                }
            }
        }
        return new VettingRequest(base.igAccount(), base.itemCount(), base.imageStyle().value().name(),
                base.owner().roster(), clusters, TIMEZONE);
    }

    /** The first captured representative among the shortcodes, as a base64 {@code data:} URL; null when none captured. */
    private String dataUrl(UUID snapshotId, List<String> shortcodes) {
        for (String shortcode : shortcodes) {
            Optional<CorpusRepresentative> rep = corpus.getRepresentative(snapshotId, shortcode);
            if (rep.isPresent()) {
                CorpusRepresentative r = rep.get();
                return "data:" + r.getContentType() + ";base64," + Base64.getEncoder().encodeToString(r.getImage());
            }
        }
        return null;
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

    /** Map the gateway verdict onto the persisted {@link AiDiscovery} facet. */
    private static AiDiscovery toDiscovery(VettingResponse v) {
        List<AiDiscovery.AiReference> references = new ArrayList<>();
        if (v.references() != null) {
            for (VettingResponse.Reference r : v.references()) {
                references.add(new AiDiscovery.AiReference(r.markerType(), r.ocrText()));
            }
        }
        return new AiDiscovery(toStyle(v.style()), v.markerType(), v.owner(), references,
                v.ocrTargetText(), v.confidence(), v.reasoning(), System.currentTimeMillis(),
                toWeeklySchedule(v.schedule()));
    }

    /** Map the gateway's per-weekday verdict onto the persisted {@link WeeklySchedule}; null when the model returned none. */
    private static WeeklySchedule toWeeklySchedule(List<VettingResponse.DaySchedule> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        List<WeeklySchedule.DaySchedule> mapped = new ArrayList<>();
        for (VettingResponse.DaySchedule d : days) {
            List<AiDiscovery.AiReference> markers = new ArrayList<>();
            if (d.markers() != null) {
                for (VettingResponse.Reference m : d.markers()) {
                    markers.add(new AiDiscovery.AiReference(m.markerType(), m.ocrText()));
                }
            }
            mapped.add(new WeeklySchedule.DaySchedule(d.weekday(), d.open(), d.groupType(), d.style(), markers,
                    d.start(), d.end(), d.endMarkerDayOffset(), d.maxTaggedPosts(), d.confidence()));
        }
        return new WeeklySchedule(TIMEZONE, mapped);
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
