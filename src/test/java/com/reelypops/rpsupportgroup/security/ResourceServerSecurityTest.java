package com.reelypops.rpsupportgroup.security;

import com.reelypops.rpsupportgroup.TestcontainersConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full resource-server security check: a real ES256 token minted with a test EC key, verified by the real
 * {@link SecurityConfig} decoder against a JWKS served from an in-process {@link MockWebServer}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ResourceServerSecurityTest {

    private static final String PROTECTED = "/supportgroup/v1/anything";

    private static MockWebServer jwksServer;
    private static ECKey ecKey;

    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void startJwks() throws Exception {
        ecKey = new ECKeyGenerator(Curve.P_256).keyID("test-key").generate();
        String jwks = new JWKSet(ecKey.toPublicJWK()).toString();
        jwksServer = new MockWebServer();
        jwksServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(jwks);
            }
        });
        jwksServer.start();
    }

    @AfterAll
    static void stopJwks() throws Exception {
        jwksServer.shutdown();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("rp.stage", () -> "dev");
        registry.add("rp.jwt.jwk-set-uri", () -> jwksServer.url("/jwks").toString());
        registry.add("rp.jwt.issuer", () -> "rpauth");
    }

    private String token(String issuer, Instant expiry) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user-123")
                .issueTime(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .expirationTime(Date.from(expiry))
                .claim("roles", List.of("SG_USER"))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(ecKey.getKeyID()).build(),
                claims);
        jwt.sign(new ECDSASigner(ecKey));
        return jwt.serialize();
    }

    private String validToken() throws JOSEException {
        return token("rpauth", Instant.now().plus(10, ChronoUnit.MINUTES));
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/health")).andExpect(status().isOk());
    }

    @Test
    void protectedEndpointWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get(PROTECTED)).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenPassesAuthentication() throws Exception {
        // Authenticated but no handler for this path -> 404 (proves the token was accepted, not 401).
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiredTokenIsUnauthorized() throws Exception {
        String expired = token("rpauth", Instant.now().minus(5, ChronoUnit.MINUTES));
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuerIsUnauthorized() throws Exception {
        String wrongIssuer = token("evil", Instant.now().plus(10, ChronoUnit.MINUTES));
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + wrongIssuer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void matchingChannelHeaderIsAllowed() throws Exception {
        mockMvc.perform(get(PROTECTED)
                        .header("Authorization", "Bearer " + validToken())
                        .header("X-App-Channel", "dev"))
                .andExpect(status().isNotFound());
    }

    @Test
    void mismatchedChannelHeaderIsForbidden() throws Exception {
        mockMvc.perform(get(PROTECTED).header("X-App-Channel", "test"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"stage_mismatch\"}"));
    }
}
