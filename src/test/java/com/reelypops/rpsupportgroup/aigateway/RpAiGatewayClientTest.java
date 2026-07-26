package com.reelypops.rpsupportgroup.aigateway;

import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient.VettingRequest;
import com.reelypops.rpsupportgroup.aigateway.RpAiGatewayClient.VettingResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The east-west client to rpaigateway's vetting endpoint: disabled (no base-url) is a fail-open no-op; when enabled it
 * presents the internal key + body and parses the verdict, and a provider/transport failure falls back to empty.
 */
class RpAiGatewayClientTest {

    private static final String BASE = "https://gw.internal";

    private static VettingRequest request() {
        return new VettingRequest("glow.grp", 10, "TEXT_OVERLAY", List.of("glow"),
                List.of(new VettingRequest.Cluster(8, List.of("glow"), 8, 0.71, 0.86, 4.85, "data:image/jpeg;base64,AQID")));
    }

    @Test
    void disabledWhenNoBaseUrl_isNoOp() {
        RpAiGatewayClient client = new RpAiGatewayClient(RestClient.builder(), "", "gw-key");

        assertThat(client.enabled()).isFalse();
        assertThat(client.vet(request())).isEmpty();
    }

    @Test
    void vet_sendsKeyAndBody_parsesTheVerdict() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RpAiGatewayClient client = new RpAiGatewayClient(builder, BASE, "gw-key");
        server.expect(requestTo(BASE + "/aigateway/v1/internal/vetting"))
                .andExpect(method(POST))
                .andExpect(header("X-Internal-Api-Key", "gw-key"))
                .andExpect(jsonPath("$.igAccount").value("glow.grp"))
                .andExpect(jsonPath("$.clusters[0].imageUrl").value("data:image/jpeg;base64,AQID"))
                .andRespond(withSuccess("""
                        {"style":"TEXT_OVERLAY","markerType":"TWO_MARKER","owner":"glow",
                         "references":[{"markerType":"start","ocrText":"Los geht's"}],
                         "ocrTargetText":"Glow","confidence":0.82,"reasoning":"two banners"}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.enabled()).isTrue();
        Optional<VettingResponse> verdict = client.vet(request());

        assertThat(verdict).isPresent();
        VettingResponse v = verdict.get();
        assertThat(v.style()).isEqualTo("TEXT_OVERLAY");
        assertThat(v.markerType()).isEqualTo("TWO_MARKER");
        assertThat(v.owner()).isEqualTo("glow");
        assertThat(v.ocrTargetText()).isEqualTo("Glow");
        assertThat(v.confidence()).isEqualTo(0.82);
        assertThat(v.reasoning()).isEqualTo("two banners");
        assertThat(v.references()).singleElement().satisfies(r -> {
            assertThat(r.markerType()).isEqualTo("start");
            assertThat(r.ocrText()).isEqualTo("Los geht's");
        });
        server.verify();
    }

    @Test
    void vet_providerFailure_fallsBackToEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RpAiGatewayClient client = new RpAiGatewayClient(builder, BASE, "gw-key");
        server.expect(requestTo(BASE + "/aigateway/v1/internal/vetting")).andRespond(withServerError());

        assertThat(client.vet(request())).isEmpty();
        server.verify();
    }
}
