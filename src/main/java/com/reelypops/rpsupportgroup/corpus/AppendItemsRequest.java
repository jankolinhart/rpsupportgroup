package com.reelypops.rpsupportgroup.corpus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** A per-scroll batch of items to append to an open snapshot (P1) — streamed as a deep scrape gathers them. */
public record AppendItemsRequest(
        @NotEmpty @Valid List<CorpusItemPayload> items) {
}
