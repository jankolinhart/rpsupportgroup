package com.reelypops.rpsupportgroup.corpus;

import jakarta.validation.constraints.NotNull;

/**
 * Open a new corpus snapshot for a group (P1): the {@link CorpusSource} plus the optional Instagram account whose
 * residential session is capturing the pass (provenance).
 */
public record OpenSnapshotRequest(
        @NotNull CorpusSource source,
        String capturedByAccount) {
}
