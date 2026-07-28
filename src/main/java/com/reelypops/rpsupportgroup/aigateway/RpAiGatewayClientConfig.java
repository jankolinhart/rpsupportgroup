package com.reelypops.rpsupportgroup.aigateway;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

/**
 * Bounds rpsupportgroup's outbound RestClient calls — its only consumer is {@link RpAiGatewayClient}. The default
 * {@code RestClient} has <strong>no read timeout</strong>, so a stuck vision-vetting call would pin a request thread
 * indefinitely. This installs a short connect timeout and a read timeout just under the internal ALB's raised
 * 300&nbsp;s idle timeout, so an over-long upstream fails with a clean client-side timeout that {@link RpAiGatewayClient#vet}
 * catches and <em>fails open</em> to Tier&nbsp;0.
 *
 * <p>Applied via a {@link RestClientCustomizer} to the Spring auto-configured {@code RestClient.Builder}; test code that
 * builds a raw {@code RestClient.builder()} (e.g. with {@code MockRestServiceServer}) is unaffected.
 */
@Configuration
public class RpAiGatewayClientConfig {

    /** Connect timeout for the vetting call — a reachable gateway answers the TCP handshake near-instantly. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Read timeout — just under the internal ALB's raised 300&nbsp;s idle timeout, so the client gives up cleanly first.
     * Sized above the observed convergent metrics-pass latency (~163&nbsp;s of gpt-5 reasoning over the compound schema)
     * with headroom, while leaving room for image prep + persistence so rpsupportgroup still returns before the outer
     * ALB idle timer fires on the calling hop.
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(240);

    @Bean
    RestClientCustomizer aiGatewayRestClientTimeouts() {
        return builder -> builder.requestFactory(timeoutRequestFactory());
    }

    /** A {@link SimpleClientHttpRequestFactory} bounded by {@link #CONNECT_TIMEOUT} + {@link #READ_TIMEOUT}. */
    static SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
