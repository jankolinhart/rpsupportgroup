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
     * Read timeout — just under the internal ALB's 600&nbsp;s idle timeout, so the client gives up cleanly first.
     *
     * <p>Raised from 240&nbsp;s on 19/08/2026, diagnosed live: on a large corpus (glowbloggeragency, 5229 items /
     * 29 clusters) the compound vet pass ran ~6 minutes. The gateway <em>completed it successfully</em> at +6:00 —
     * 72 seconds after this client had already given up at its timeout — and the verdict was thrown away, because
     * this service is the one that stores it. The admin UI then showed a green tick and "AI discovery has not
     * been run": three fail-opens deep, nothing anywhere said a thing. 570&nbsp;s nests under the ALB's 600 so a
     * genuine hang still fails on THIS side, with a parseable timeout instead of the ALB's octet-stream body.</p>
     *
     * <p>⚠️ Stopgap. A synchronous chain wrapped around a multi-minute AI call is always one corpus-growth-spurt
     * away from this failure — the real fix (urgent TODO, rpdocu) is an asynchronous pass: fire, persist
     * server-side on completion, poll from the UI. Then no timeout chain exists to outrun.</p>
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(570);

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
