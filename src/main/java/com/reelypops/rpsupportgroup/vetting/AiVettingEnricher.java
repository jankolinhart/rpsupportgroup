package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.corpus.CorpusSnapshotItem;

import java.util.List;

/**
 * The seam for the higher vetting tiers (P3): given the cheap Tier&nbsp;0 (dHash-cluster) proposal and the raw grid
 * items, an implementation may refine it — Tier&nbsp;1 (a text LLM over cluster summaries) and Tier&nbsp;2 (vision on the
 * representatives) — typically when {@link DetectorProfileProposal#escalate()} is set. Enrichment is <strong>advisory
 * and fail-open</strong>: the default {@link NoOpAiVettingEnricher} returns the Tier&nbsp;0 proposal unchanged, so the
 * product vets manually with the AI off. A real, {@code rpaigateway}-backed implementation is a later, deliberately
 * deferred decision (provider / model), wired in without touching the Tier&nbsp;0 engine.
 */
public interface AiVettingEnricher {

    /** Refine (or pass through) a Tier&nbsp;0 proposal. Must never throw for a valid proposal — fail open. */
    DetectorProfileProposal enrich(DetectorProfileProposal tier0, List<CorpusSnapshotItem> items);
}
