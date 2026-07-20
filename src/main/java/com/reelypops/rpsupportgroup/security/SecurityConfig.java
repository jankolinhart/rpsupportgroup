package com.reelypops.rpsupportgroup.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Resource-server security: every request under {@code /supportgroup/v1/**} (except the health probe) must
 * carry a valid access token issued by rpauth. Tokens are ES256-signed JWTs verified against rpauth's public
 * JWKS; the {@code iss} claim and expiry are checked, and the {@code roles} claim becomes Spring authorities
 * (the SG RBAC roles — SG_ADMIN / SG_MANAGER / SG_USER — federate here, §7.9).
 *
 * <p>Stage isolation (D19) is enforced by {@link AppChannelAffinityFilter}: each stage runs its own rpauth with
 * its own signing key, so a token minted on one stage cannot validate on another; the {@code X-App-Channel}
 * header adds a defence-in-depth check that a client's declared channel matches this deployment's stage.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Internal service-to-service chain: {@code /supportgroup/v1/internal/**} is authenticated by the shared
     * {@code X-Internal-Api-Key} (no end-user JWT, no channel-affinity check), so the scanner and the
     * rpadminserver BFF can read SG configs east-west. A missing or wrong key yields {@code 401}.
     */
    @Bean
    @Order(1)
    SecurityFilterChain internalApiChain(HttpSecurity http,
                                         @Value("${rp.internal.api-key:}") String apiKey) throws Exception {
        http
            .securityMatcher("/supportgroup/v1/internal/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INTERNAL"))
            .exceptionHandling(e -> e.authenticationEntryPoint(
                (req, res, ex) -> res.setStatus(HttpStatus.UNAUTHORIZED.value())))
            .addFilterBefore(new InternalApiKeyFilter(apiKey), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http,
                                    JwtDecoder jwtDecoder,
                                    @Value("${rp.stage:prod}") String stage) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/supportgroup/v1/health", "/actuator/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(rolesConverter())))
            .addFilterBefore(new AppChannelAffinityFilter(stage), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * ES256 JWT decoder bound to rpauth's JWKS for this stage, validating signature, expiry and issuer. The
     * JWKS URL is derived from the stage but can be overridden explicitly (e.g. in tests) via
     * {@code rp.jwt.jwk-set-uri} / {@code JWT_JWK_SET_URI}.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${rp.stage:prod}") String stage,
                          @Value("${rp.jwt.jwk-set-uri:}") String jwkSetUriOverride,
                          @Value("${rp.jwt.issuer:rpauth}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(jwksUri(stage, jwkSetUriOverride))
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    /** rpauth's per-stage public JWKS endpoint (served on the stage's apiDomain, internal ALB east-west). */
    static String jwksUri(String stage, String override) {
        if (!override.isBlank()) {
            return override;
        }
        String host = switch (stage.toLowerCase()) {
            case "dev"  -> "devapi.reelypops.com";
            case "test" -> "testapi.reelypops.com";
            default     -> "api.reelypops.com";
        };
        return "https://" + host + "/auth/v1/.well-known/jwks";
    }

    /** Maps rpauth's {@code roles} claim onto {@code ROLE_*} authorities. */
    private static JwtAuthenticationConverter rolesConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
