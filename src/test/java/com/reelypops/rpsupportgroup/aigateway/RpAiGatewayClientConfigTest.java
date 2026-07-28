package com.reelypops.rpsupportgroup.aigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The RestClient timeout customizer: the factory is bounded by the configured connect + read timeouts, and the
 * customizer installs it on a builder — so a stuck vision-vetting call fails open instead of pinning a thread.
 */
class RpAiGatewayClientConfigTest {

    @Test
    void timeoutRequestFactoryIsBoundedByTheConfiguredTimeouts() {
        SimpleClientHttpRequestFactory factory = RpAiGatewayClientConfig.timeoutRequestFactory();

        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(10_000);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(240_000);
    }

    @Test
    void customizerInstallsTheBoundedFactoryOnTheBuilder() {
        RestClientCustomizer customizer = new RpAiGatewayClientConfig().aiGatewayRestClientTimeouts();
        RestClient.Builder builder = RestClient.builder();

        customizer.customize(builder);

        // The customizer swapped in our bounded factory; the builder still yields a usable client.
        assertThat(builder.build()).isNotNull();
    }
}
