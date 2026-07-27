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
     * @param owner         the marker-owner account, or null
     * @param references    the confirmed marker references
     * @param ocrTargetText the stable overlay text for a text-overlay group, else null
     * @param confidence    0..1
     * @param reasoning     a short justification
     * @param schedule      the per-weekday schedule verdict (M4.5) — one entry per weekday the model reported
     */
    public record VettingResponse(String style, String markerType, String owner, List<Reference> references,
                                  String ocrTargetText, double confidence, String reasoning,
                                  List<DaySchedule> schedule) {

        /** One AI-confirmed marker reference: its round slot + OCR text. */
        public record Reference(String markerType, String ocrText) {
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
