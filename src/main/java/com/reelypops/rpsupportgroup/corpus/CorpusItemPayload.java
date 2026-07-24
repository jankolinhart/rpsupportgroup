package com.reelypops.rpsupportgroup.corpus;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * One tagged-grid post streamed into an open snapshot (P1): metadata + perceptual {@code dHash} (always present),
 * the grid {@code ordinal} (taggedAt order), and the post's {@code postedAt} when known.
 */
public record CorpusItemPayload(
        @NotBlank String shortcode,
        @NotBlank String authorUsername,
        @NotBlank String dHash,
        Instant postedAt,
        int ordinal) {
}
