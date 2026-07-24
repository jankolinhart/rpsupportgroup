package com.reelypops.rpsupportgroup.corpus;

/**
 * Where a {@link MarkerCorpusSnapshot} came from (P1). A group's corpus accumulates whole snapshots over time from
 * several residential sources: a client's first foreground {@code REQUEST} scrape, an ongoing round-robin {@code DUTY}
 * scrape, an operator {@code ADMIN}-mode deep scrape, and (later) the {@code SCANNER} (rpsgscanner) — which contributes
 * through the very same intake API.
 */
public enum CorpusSource {
    REQUEST,
    DUTY,
    ADMIN,
    SCANNER
}
