package com.reelypops.rpsupportgroup.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void healthReturnsOk() {
        assertThat(new HealthController().health()).containsEntry("status", "ok");
    }
}
