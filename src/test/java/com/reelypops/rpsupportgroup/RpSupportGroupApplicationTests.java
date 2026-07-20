package com.reelypops.rpsupportgroup;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Verifies the Spring application context loads correctly (web + security + JPA against Testcontainers Postgres).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RpSupportGroupApplicationTests {

    @Test
    void contextLoads() {
        // Spring Boot context must load without errors
    }
}
