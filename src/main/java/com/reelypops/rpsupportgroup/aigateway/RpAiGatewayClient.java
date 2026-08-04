package com.reelypops.rpsupportgroup.aigateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/**
 * The east-west client to <strong>rpaigateway</strong>'s backend-internal marker-vetting endpoint (marker-auto-discovery
 * M4). Presents the gateway's shared {@code X-Internal-Api-Key}. It is <strong>advisory + fail-open</strong>: when the
 * gateway URL is not configured (local/test, and any stage before the AI tier is enabled) the client is
 * <em>disabled</em> and every call returns {@link Optional#empty()}; a transport/provider failure is swallowed the same
 * way, so AI discovery never blocks Tier-0 vetting.
 */
@Component
public class RpAiGatewayClient {

    static final String API_KEY_HEADER = "X-Internal-Api-Key";
    static final String VETTING_PATH = "/aigateway/v1/internal/vetting";
    static final String REFINE_PATH = "/aigateway/v1/internal/vetting/refine";
    static final String READ_PATH = "/aigateway/v1/internal/vetting/read";
    static final String OCR_IMAGE_PATH = "/aigateway/v1/internal/vetting/ocr-image";

    private static final Logger log = LoggerFactory.getLogger(RpAiGatewayClient.class);

    private final RestClient restClient;
    private final boolean enabled;

    public RpAiGatewayClient(RestClient.Builder builder,
                             @Value("${rp.aigateway.base-url:}") String baseUrl,
                             @Value("${rp.aigateway.internal-api-key:}") String apiKey) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.restClient = enabled
                ? builder.baseUrl(baseUrl).defaultHeader(API_KEY_HEADER, apiKey).build()
                : null;
    }

    /** True when a gateway URL is configured; otherwise AI discovery is off and {@link #vet} is a no-op. */
    public boolean enabled() {
        return enabled;
    }

    /** Ask the AI tier to judge a Tier-0 cluster summary + representatives; empty when disabled or on any failure. */
    public Optional<VettingResponse> vet(VettingRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(restClient.post().uri(VETTING_PATH).body(request)
                    .retrieve().body(VettingResponse.class));
        } catch (RestClientException e) {
            log.warn("AI vetting call failed, falling back to Tier 0: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The convergent refinement pass: ask the AI tier for the DISTINCT marker templates still missing from
     * {@code alreadyFound}. Empty when disabled or on any failure (the caller reads that as convergence and stops).
     */
    public Optional<RefineResponse> refine(RefineRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(restClient.post().uri(REFINE_PATH).body(request)
                    .retrieve().body(RefineResponse.class));
        } catch (RestClientException e) {
            log.warn("AI refine call failed, treating as converged: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The isolated per-image OCR pass: read each candidate image on its own so the returned marker text always matches
     * its image (no cross-image confabulation). Empty when disabled or on any failure (the caller keeps the compound
     * verdict / treats a refine as converged).
     */
    public Optional<ReadResponse> read(ReadRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(restClient.post().uri(READ_PATH).body(request)
                    .retrieve().body(ReadResponse.class));
        } catch (RestClientException e) {
            log.warn("AI read (per-image OCR) call failed, keeping the compound gallery: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Single-image OCR (Vetting Portal hand-added marker): transcribe the overlay text on ONE operator-chosen image so a
     * {@code TEXT_OVERLAY} marker built from the corpus grid or an upload carries the OCR target text the client matches
     * on. Empty when disabled or on any failure — the operator then types the text in by hand. The gateway reads the
     * image <em>unconditionally</em> (it never gates on an "is this a marker" classification).
     */
    public Optional<OcrImageResponse> ocrImage(OcrImageRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(restClient.post().uri(OCR_IMAGE_PATH).body(request)
                    .retrieve().body(OcrImageResponse.class));
        } catch (RestClientException e) {
            log.warn("AI single-image OCR call failed, leaving the marker text blank: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The refinement request (mirrors the gateway's contract): the same numbered candidate {@link VettingRequest.Cluster
     * clusters} (representative images) plus the marker texts already identified, so the AI returns only the DISTINCT
     * templates still missing.
     *
     * @param igAccount    the support group's Instagram account
     * @param clusters     the recurring-image clusters (representative images), same order as the metrics pass
     * @param alreadyFound the marker overlay texts already identified (null/empty on the first refinement pass)
     */
    public record RefineRequest(String igAccount, List<VettingRequest.Cluster> clusters, List<String> alreadyFound) {
    }

    /**
     * The refinement verdict (mirrors the gateway's contract): the newly-recognised distinct marker references (empty =
     * converged) plus the pass's token {@link Usage usage}.
     */
    public record RefineResponse(List<VettingResponse.Reference> newMarkers, Usage usage) {
    }

    /**
     * The isolated per-image OCR request (mirrors the gateway's contract): the image-bearing candidate
     * {@link VettingRequest.Cluster clusters} to read one-by-one. Only image-bearing clusters are sent, so a returned
     * {@code clusterIndex} (1-based image number) lines up with the caller's aligned shortcode list.
     *
     * @param igAccount the support group's Instagram account
     * @param clusters  the image-bearing candidate clusters, in the order their images are read
     */
    public record ReadRequest(String igAccount, List<VettingRequest.Cluster> clusters) {
    }

    /**
     * The isolated per-image OCR verdict (mirrors the gateway's contract): {@code markers} = the DISTINCT markers (one
     * per text, grounded to the image it was read from); {@code markerReads} = EVERY marker-bearing image's read (NOT
     * deduped) so a marker text can be tied to ALL its cluster images for per-weekday timing attribution; plus the
     * pass's token {@link Usage usage}.
     */
    public record ReadResponse(List<VettingResponse.Reference> markers,
                               List<VettingResponse.Reference> markerReads, Usage usage) {
    }

    /**
     * The single-image OCR request (mirrors the gateway's contract): the image to read as a {@code data:} URL (base64
     * bytes) or an http URL. {@code igAccount} is optional logging context ({@code null} for an upload, which has no
     * group scope).
     */
    public record OcrImageRequest(String igAccount, String imageUrl) {
    }

    /** The single-image OCR reply (mirrors the gateway's contract): the overlay text read, or {@code null} when none. */
    public record OcrImageResponse(String ocrText) {
    }

    /**
     * The provider token spend + ESTIMATED cost of one AI pass (mirrors the gateway's contract) — surfaced to the admin
     * discovery progress UI. {@code costEstimate} is a configured-price estimate (a plain decimal string), not billed.
     */
    public record Usage(String model, long promptTokens, long completionTokens, String costEstimate, String currency) {
    }

    /**
     * The reduced Tier-0 summary sent to the gateway (mirrors its request contract).
     *
     * @param igAccount   the support group's Instagram account
     * @param itemCount   how many grid posts Tier 0 considered
     * @param tier0Style  the local pass's style guess
     * @param ownerRoster the Tier-0 candidate marker-owners
     * @param clusters    the recurring-image clusters (metrics + one representative image each)
     * @param timezone    the timezone the cluster occurrence times are expressed in (M4.5); {@code UTC} in v1
     * @param gridWindow  the recent tagged-grid window in true order (M4.7) — the ordered timeline the AI reconstructs
     *                    rounds + per-round member counts from
     */
    public record VettingRequest(String igAccount, int itemCount, String tier0Style, List<String> ownerRoster,
                                 List<Cluster> clusters, String timezone, List<GridRow> gridWindow) {

        /**
         * One cluster: its Tier-0 metrics, a representative image (a {@code data:} URL, or null when uncaptured), and
         * its marker posts' {@link Occurrence timings} so the AI can derive a per-weekday schedule (M4.5).
         */
        public record Cluster(int size, List<String> authors, int recurrence, double cadenceRegularity,
                              double coverage, double score, String imageUrl, List<Occurrence> occurrences) {
        }

        /** One marker post's timing (weekday + {@code HH:mm}) in the request {@code timezone}, for M4.5. */
        public record Occurrence(String weekday, String timeOfDayLocal) {
        }

        /**
         * One recent tagged-grid row (M4.7): its grid {@code ordinal} (taggedAt order — newest tag first, P5), the
         * {@code author}, and whether it is a {@code marker} (a post by the proposed owner). The ordered sequence lets
         * the AI bracket rounds (a START→END marker pair) and count the member posts inside each round.
         */
        public record GridRow(int ordinal, String author, boolean marker) {
        }
    }

    /**
     * The AI tier's structured verdict (mirrors the gateway's response contract).
     *
     * @param style         FLAT_BANNER / TEXT_OVERLAY
     * @param markerType    SINGLE_MARKER / TWO_MARKER / CONTINUOUS / UNKNOWN
     * @param owner         the PRIMARY marker-owner account (first of {@code owners}), or null
     * @param owners        the marker-owner SET (a duty rota has several); may be null/empty from an older gateway
     * @param references    the confirmed marker references
     * @param ocrTargetText the stable overlay text for a text-overlay group, else null
     * @param confidence    0..1
     * @param reasoning     a short justification
     * @param schedule      the per-weekday schedule verdict (M4.5) — one entry per weekday the model reported
     */
    public record VettingResponse(String style, String markerType, String owner, List<String> owners,
                                  List<Reference> references, String ocrTargetText, double confidence, String reasoning,
                                  List<DaySchedule> schedule, Usage usage) {

        /** One AI-confirmed marker reference: its round slot, OCR text, and the 1-based cited cluster index (A2/B7b). */
        public record Reference(String markerType, String ocrText, Integer clusterIndex) {
        }

        /**
         * One weekday's schedule in the AI's per-weekday verdict (M4.5): open/closed + the day's type, style, markers,
         * round times ({@code HH:mm}), end-marker day offset, per-member max-tagged-posts, and confidence.
         */
        public record DaySchedule(String weekday, boolean open, String groupType, String style,
                                  List<Reference> markers, String start, String end,
                                  Integer endMarkerDayOffset, Integer maxTaggedPosts, Double confidence) {
        }
    }
}
