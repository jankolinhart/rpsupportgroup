package com.reelypops.rpsupportgroup.corpus;

/**
 * The lifecycle of a {@link MarkerCorpusSnapshot} (P1). A snapshot is {@code OPEN} while a deep scrape streams its
 * items in per scroll; it is {@code SEALED} once the pass completes, or swept to {@code INTERRUPTED} when a client
 * abandons an open pass past the stale TTL (P1 GC). An interrupted/partial pass's already-shipped items remain usable
 * (take-what-we-get).
 *
 * <p>⚠️ {@code REJECTED} is different in kind, and is the ONE status whose items are never usable. A deep scrape
 * builds the corpus that vetted marker references are cut from, and those references are then matched by every
 * other client at a 4–10 bit tolerance. A single malformed fingerprint streamed by one client would therefore
 * become a reference that no client can ever match — so a snapshot carrying one is voided whole and the pass is
 * re-run, rather than salvaged. The user's call, 16/08/2026: <em>"I'd rather retry the entire snapshot than risk
 * image hash corruption sent to the cloud by a single client that affects all clients after."</em>
 */
public enum SnapshotStatus {
    OPEN,
    SEALED,
    INTERRUPTED,
    REJECTED
}
