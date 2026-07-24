package com.reelypops.rpsupportgroup.corpus;

/**
 * The lifecycle of a {@link MarkerCorpusSnapshot} (P1). A snapshot is {@code OPEN} while a deep scrape streams its
 * items in per scroll; it is {@code SEALED} once the pass completes, or swept to {@code INTERRUPTED} when a client
 * abandons an open pass past the stale TTL (P1 GC). An interrupted/partial pass's already-shipped items remain usable
 * (take-what-we-get).
 */
public enum SnapshotStatus {
    OPEN,
    SEALED,
    INTERRUPTED
}
