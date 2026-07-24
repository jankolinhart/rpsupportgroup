package com.reelypops.rpsupportgroup.corpus;

/**
 * The lifecycle of a {@link MarkerCorpusSnapshot} (P1). A snapshot is {@code OPEN} while a deep scrape streams its
 * items in per scroll; it is {@code SEALED} once the pass completes. An interrupted pass simply stays partial — the
 * items already shipped remain usable (take-what-we-get).
 */
public enum SnapshotStatus {
    OPEN,
    SEALED
}
