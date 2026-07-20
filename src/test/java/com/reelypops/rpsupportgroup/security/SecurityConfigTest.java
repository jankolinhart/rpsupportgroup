package com.reelypops.rpsupportgroup.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void derivesDevJwksUri() {
        assertThat(SecurityConfig.jwksUri("dev", ""))
                .isEqualTo("https://devapi.reelypops.com/auth/v1/.well-known/jwks");
    }

    @Test
    void derivesTestJwksUri() {
        assertThat(SecurityConfig.jwksUri("test", ""))
                .isEqualTo("https://testapi.reelypops.com/auth/v1/.well-known/jwks");
    }

    @Test
    void derivesProdJwksUriByDefault() {
        assertThat(SecurityConfig.jwksUri("prod", ""))
                .isEqualTo("https://api.reelypops.com/auth/v1/.well-known/jwks");
    }

    @Test
    void explicitOverrideWins() {
        assertThat(SecurityConfig.jwksUri("dev", "http://localhost:1234/jwks"))
                .isEqualTo("http://localhost:1234/jwks");
    }
}
