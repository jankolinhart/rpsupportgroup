package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient;
import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient.ReadRequest;
import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient.ReadResponse;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    // Run-history pass kinds (persisted on AiDiscovery.passes): the first compound+read metrics pass, then refine passes.
    private static final String PASS_METRICS = "METRICS";
    private static final String PASS_REFINE = "REFINE";

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
                              @Value("${rp.aigateway.max-images:60}") int maxImages,
                              @Value("${rp.aigateway.samples-per-cluster:8}") int perClusterSamples) {
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
        ImageSubset images = imageSubset(aiRequest);
        // The metrics pass is TWO gateway calls run CONCURRENTLY so latency stays ~flat: vet (the compound verdict —
        // style / type / owner / schedule) and read (the isolated per-image OCR that GROUNDS the marker gallery so a
        // marker's text always matches its image). Both fail open (Optional), so the joins never throw.
        CompletableFuture<Optional<VettingResponse>> vetFuture =
                CompletableFuture.supplyAsync(() -> gateway.vet(aiRequest.request()));
        CompletableFuture<Optional<ReadResponse>> readFuture =
                CompletableFuture.supplyAsync(() -> gateway.read(new ReadRequest(base.igAccount(), images.clusters())));
        Optional<VettingResponse> verdict = vetFuture.join();
        Optional<ReadResponse> read = readFuture.join();
        if (verdict.isEmpty()) {
            return base; // vet is the only source of style / type / owner / schedule — without it we stay Tier-0
        }
        // Marker gallery: prefer the grounded per-image OCR read; fall back to the compound verdict when read is down.
        List<AiDiscovery.AiReference> references = read.isPresent()
                ? toReferences(read.get().markers(), images.shortcodes())
                : toReferences(verdict.get().references(), aiRequest.clusterShortcodes());
        // Grounded per-weekday markers: bucket each read marker's cluster occurrences by weekday (null when read is down
        // → the schedule keeps the compound per-day markers).
        Map<String, List<AiDiscovery.AiReference>> weekdayMarkers =
                read.map(r -> groundedWeekdayMarkers(r.markerReads(), images)).orElse(null);
        AiDiscovery.Usage usage = combineUsage(verdict.get().usage(), read.map(ReadResponse::usage).orElse(null));
        // Record the metrics pass in the run history (markersAdded = the whole gallery it produced; never "converged").
        AiDiscovery.AiPass metricsPass = pass(PASS_METRICS, usage, references.size(), false);
        DetectedProfile enriched = withAi(base,
                toDiscovery(verdict.get(), base.owner().roster(), references, usage, aiRequest.clusterShortcodes(),
                        weekdayMarkers, List.of(metricsPass)));
        SupportGroupConfig config = configs.findByIgAccount(base.igAccount())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no config for " + base.igAccount()));
        config.updateDetectedProfile(enriched);
        configs.save(config);
        return enriched;
    }

    /**
     * Convergent refinement (second..Nth pass): re-send the same candidate images + the marker texts already found and
     * ask the AI tier for the DISTINCT templates still MISSING, then MERGE any new ones into the stored advisory. Loads
     * the persisted advisory (it does NOT re-run Tier-0 {@code detect}, which would wipe it) and returns how many markers
     * it added — {@code added == 0} means the set has converged. A no-op (added 0) when there is no metrics-pass advisory
     * yet, or the gateway is disabled/unavailable. 404 if the snapshot or its config is unknown.
     */
    @Transactional
    public AiRefineResult refineAiDiscovery(UUID snapshotId) {
        String igAccount = proposals.analyze(snapshotId).proposal().igAccount();
        SupportGroupConfig config = configs.findByIgAccount(igAccount)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no config for " + igAccount));
        DetectedProfile stored = config.getDetectedProfile();
        AiDiscovery ai = stored == null ? null : stored.aiDiscovery();
        if (ai == null) {
            return new AiRefineResult(stored, 0); // nothing to refine — the metrics pass has not run
        }
        AiRequest aiRequest = buildRequest(snapshotId, stored);
        ImageSubset images = imageSubset(aiRequest);
        Optional<ReadResponse> read = gateway.read(new ReadRequest(igAccount, images.clusters()));
        if (read.isEmpty()) {
            return new AiRefineResult(stored, 0); // gateway off / failed → treated as converged (loop stops)
        }
        List<AiDiscovery.AiReference> merged = new ArrayList<>(ai.references());
        Set<String> seen = new HashSet<>();
        for (AiDiscovery.AiReference r : ai.references()) {
            if (r.ocrText() != null && !r.ocrText().isBlank()) {
                seen.add(normalise(r.ocrText()));
            }
        }
        int added = 0;
        List<VettingResponse.Reference> markers = read.get().markers();
        if (markers != null) {
            for (VettingResponse.Reference r : markers) {
                if (r.ocrText() == null || r.ocrText().isBlank() || !seen.add(normalise(r.ocrText()))) {
                    continue; // blank or a text we already carry — a re-read of the complete set adds nothing (converged)
                }
                merged.add(new AiDiscovery.AiReference(r.markerType(), r.ocrText(),
                        shortcodeFor(r.clusterIndex(), images.shortcodes()), null));
                added++;
            }
        }
        AiDiscovery.Usage refineUsage = toUsage(read.get().usage());
        List<AiDiscovery.AiPass> passes = new ArrayList<>(ai.passes() == null ? List.of() : ai.passes());
        passes.add(pass(PASS_REFINE, refineUsage, added, added == 0));
        AiDiscovery updated = new AiDiscovery(ai.style(), ai.markerType(), ai.owner(), ai.owners(), merged, ai.ocrTargetText(),
                ai.confidence(), ai.reasoning(), System.currentTimeMillis(), ai.weeklySchedule(),
                refineUsage, passes);
        DetectedProfile result = withAi(stored, updated);
        config.updateDetectedProfile(result);
        configs.save(config);
        return new AiRefineResult(result, added);
    }

    /** The dedup key for a marker text: lowercased, whitespace-stripped (matches the gateway's own dedup key). */
    private static String normalise(String ocrText) {
        return ocrText.toLowerCase().replaceAll("\\s+", "");
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

    /**
     * Map the gateway verdict + the grounded marker references onto the persisted {@link AiDiscovery} facet. The owner
     * facet is the UNION of the deterministic Tier-0 roster ({@code tier0Owners}, authoritative — it counts posts) and
     * the AI's owners, Tier-0 first (share-ranked). Vision may ADD a handle it reads in text but never DROPS a
     * Tier-0-confirmed co-owner — otherwise a shared-banner duty rota collapses to a single AI owner (M6, vision §5b).
     */
    private static AiDiscovery toDiscovery(VettingResponse v, List<String> tier0Owners,
                                           List<AiDiscovery.AiReference> references,
                                           AiDiscovery.Usage usage, List<String> clusterShortcodes,
                                           Map<String, List<AiDiscovery.AiReference>> weekdayMarkers,
                                           List<AiDiscovery.AiPass> passes) {
        List<String> aiOwners = v.owners() != null && !v.owners().isEmpty()
                ? v.owners()
                : (v.owner() != null ? List.of(v.owner()) : List.of());
        // Tier-0 GROUNDING (M6): keep every deterministically-counted owner, then append any AI-only handle.
        LinkedHashSet<String> owners = new LinkedHashSet<>(tier0Owners == null ? List.of() : tier0Owners);
        owners.addAll(aiOwners);
        return new AiDiscovery(toStyle(v.style()), v.markerType(), v.owner(), List.copyOf(owners), references,
                v.ocrTargetText(), v.confidence(), v.reasoning(), System.currentTimeMillis(),
                toWeeklySchedule(v.schedule(), clusterShortcodes, weekdayMarkers), usage, passes);
    }

    /** Resolve gateway references (compound OR per-image OCR) to persisted references, each cited image → its shortcode. */
    private static List<AiDiscovery.AiReference> toReferences(List<VettingResponse.Reference> refs,
                                                             List<String> shortcodes) {
        List<AiDiscovery.AiReference> out = new ArrayList<>();
        if (refs != null) {
            for (VettingResponse.Reference r : refs) {
                out.add(toAiReference(r, shortcodes));
            }
        }
        return out;
    }

    /** Sum the metrics pass's two gateway calls' spend (compound vet + per-image read) into one figure for the cost UI. */
    private static AiDiscovery.Usage combineUsage(RpAiGatewayClient.Usage vet, RpAiGatewayClient.Usage read) {
        if (vet == null) {
            return toUsage(read);
        }
        if (read == null) {
            return toUsage(vet);
        }
        String cost = new BigDecimal(vet.costEstimate()).add(new BigDecimal(read.costEstimate())).toPlainString();
        return new AiDiscovery.Usage(vet.model(), vet.promptTokens() + read.promptTokens(),
                vet.completionTokens() + read.completionTokens(), cost, vet.currency());
    }

    /** The image-bearing subset of the request — cluster + aligned shortcode — for the isolated per-image OCR read pass. */
    private static ImageSubset imageSubset(AiRequest aiRequest) {
        List<VettingRequest.Cluster> clusters = new ArrayList<>();
        List<String> shortcodes = new ArrayList<>();
        List<VettingRequest.Cluster> all = aiRequest.request().clusters();
        List<String> allShortcodes = aiRequest.clusterShortcodes();
        for (int i = 0; all != null && i < all.size(); i++) {
            VettingRequest.Cluster c = all.get(i);
            if (c.imageUrl() != null && !c.imageUrl().isBlank()) {
                clusters.add(c);
                shortcodes.add(allShortcodes.get(i));
            }
        }
        return new ImageSubset(clusters, shortcodes);
    }

    /** The image-bearing clusters + their aligned shortcodes — the image-number space the OCR read pass cites into. */
    private record ImageSubset(List<VettingRequest.Cluster> clusters, List<String> shortcodes) {
    }

    /** Map the gateway's per-pass token usage onto the persisted {@link AiDiscovery.Usage}; null when the gateway sent none. */
    private static AiDiscovery.Usage toUsage(RpAiGatewayClient.Usage u) {
        return u == null ? null : new AiDiscovery.Usage(u.model(), u.promptTokens(), u.completionTokens(),
                u.costEstimate(), u.currency());
    }

    /** One run-history entry built from a pass's usage (null usage → a zero-spend entry) + how many markers it added. */
    private static AiDiscovery.AiPass pass(String kind, AiDiscovery.Usage usage, int markersAdded, boolean converged) {
        long ranAtMs = System.currentTimeMillis();
        if (usage == null) {
            return new AiDiscovery.AiPass(kind, ranAtMs, null, 0, 0, null, null, markersAdded, converged);
        }
        return new AiDiscovery.AiPass(kind, ranAtMs, usage.model(), usage.promptTokens(), usage.completionTokens(),
                usage.costEstimate(), usage.currency(), markersAdded, converged);
    }

    /**
     * Map the gateway's per-weekday verdict onto the persisted {@link WeeklySchedule}, replacing each day's markers with
     * the GROUNDED per-weekday markers when the read pass ran ({@code weekdayMarkers != null}) — else keeping the
     * compound day markers. Adds a day for any grounded weekday the compound pass didn't report. Null when neither.
     */
    private static WeeklySchedule toWeeklySchedule(List<VettingResponse.DaySchedule> days, List<String> clusterShortcodes,
                                                   Map<String, List<AiDiscovery.AiReference>> weekdayMarkers) {
        boolean hasCompound = days != null && !days.isEmpty();
        if (!hasCompound && (weekdayMarkers == null || weekdayMarkers.isEmpty())) {
            return null;
        }
        List<WeeklySchedule.DaySchedule> mapped = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        if (days != null) {
            for (VettingResponse.DaySchedule d : days) {
                covered.add(d.weekday());
                List<AiDiscovery.AiReference> markers = weekdayMarkers != null
                        ? new ArrayList<>(weekdayMarkers.getOrDefault(d.weekday(), List.of()))
                        : compoundMarkers(d.markers(), clusterShortcodes);
                mapped.add(new WeeklySchedule.DaySchedule(d.weekday(), d.open(), d.groupType(), d.style(), markers,
                        d.start(), d.end(), d.endMarkerDayOffset(), d.maxTaggedPosts(), d.confidence()));
            }
        }
        if (weekdayMarkers != null) {
            for (Map.Entry<String, List<AiDiscovery.AiReference>> e : weekdayMarkers.entrySet()) {
                if (!covered.contains(e.getKey())) {
                    mapped.add(new WeeklySchedule.DaySchedule(e.getKey(), true, null, null,
                            new ArrayList<>(e.getValue()), null, null, null, null, null));
                }
            }
        }
        mapped.sort(Comparator.comparingInt(d -> weekdayOrder(d.weekday())));
        return new WeeklySchedule(TIMEZONE, mapped);
    }

    /** The compound verdict's per-day markers, mapped to references (used only when the grounded read pass is down). */
    private static List<AiDiscovery.AiReference> compoundMarkers(List<VettingResponse.Reference> refs,
                                                                 List<String> clusterShortcodes) {
        List<AiDiscovery.AiReference> markers = new ArrayList<>();
        if (refs != null) {
            for (VettingResponse.Reference m : refs) {
                markers.add(toAiReference(m, clusterShortcodes));
            }
        }
        return markers;
    }

    /**
     * GROUNDED per-weekday marker attribution (deterministic): group the per-image marker reads by text, union each
     * marker's clusters' post {@code occurrences}, and bucket by weekday → the days each marker appears on, each with a
     * count-based recurrence confidence and its role. Returns weekday ({@code MON}…{@code SUN}) → the markers seen.
     */
    private static Map<String, List<AiDiscovery.AiReference>> groundedWeekdayMarkers(
            List<VettingResponse.Reference> markerReads, ImageSubset images) {
        Map<String, MarkerWeekdays> byText = new LinkedHashMap<>();
        if (markerReads != null) {
            for (VettingResponse.Reference read : markerReads) {
                Integer idx = read.clusterIndex();
                if (read.ocrText() == null || read.ocrText().isBlank()
                        || idx == null || idx < 1 || idx > images.clusters().size()) {
                    continue;
                }
                MarkerWeekdays mw = byText.computeIfAbsent(normalise(read.ocrText()),
                        k -> new MarkerWeekdays(read.markerType(), read.ocrText(), images.shortcodes().get(idx - 1)));
                for (VettingRequest.Occurrence occ : images.clusters().get(idx - 1).occurrences()) {
                    mw.add(occ.weekday(), occ.timeOfDayLocal());
                }
            }
        }
        Map<String, List<AiDiscovery.AiReference>> byWeekday = new LinkedHashMap<>();
        for (MarkerWeekdays mw : byText.values()) {
            for (Map.Entry<String, Integer> e : mw.weekdayCounts().entrySet()) {
                byWeekday.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new AiDiscovery.AiReference(mw.markerType(), mw.ocrText(), mw.shortcode(),
                                recurrenceConfidence(e.getValue())));
            }
        }
        return byWeekday;
    }

    /** Count-based recurrence confidence: more distinct sightings on a weekday → higher, saturating (1→0.5, 2→0.75…). */
    private static double recurrenceConfidence(int distinctSightings) {
        return 1.0 - Math.pow(0.5, distinctSightings);
    }

    private static final List<String> WEEKDAY_ORDER = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    /** Sort key MON…SUN; -1 (sorts first) for an unexpected value — occurrences only ever emit MON…SUN. */
    private static int weekdayOrder(String weekday) {
        return WEEKDAY_ORDER.indexOf(weekday);
    }

    /** Accumulates one marker's DISTINCT (weekday, HH:mm) sightings across its clusters, for per-weekday counting. */
    private static final class MarkerWeekdays {
        private final String markerType;
        private final String ocrText;
        private final String shortcode;
        private final Set<String> seen = new HashSet<>();
        private final Map<String, Integer> weekdayCounts = new LinkedHashMap<>();

        MarkerWeekdays(String markerType, String ocrText, String shortcode) {
            this.markerType = markerType;
            this.ocrText = ocrText;
            this.shortcode = shortcode;
        }

        void add(String weekday, String time) {
            if (seen.add(weekday + "|" + time)) { // drop repeats from multiple images of one cluster (same occurrences)
                weekdayCounts.merge(weekday, 1, Integer::sum);
            }
        }

        String markerType() {
            return markerType;
        }

        String ocrText() {
            return ocrText;
        }

        String shortcode() {
            return shortcode;
        }

        Map<String, Integer> weekdayCounts() {
            return weekdayCounts;
        }
    }

    /** One AI reference with the representative shortcode of its cited cluster resolved (A2/B7b); no per-marker confidence. */
    private static AiDiscovery.AiReference toAiReference(VettingResponse.Reference r, List<String> clusterShortcodes) {
        return new AiDiscovery.AiReference(r.markerType(), r.ocrText(),
                shortcodeFor(r.clusterIndex(), clusterShortcodes), null);
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
