package com.reelypops.rpsupportgroup.corpus;

/**
 * The lifecycle of a {@link MarkerCorpusSnapshot} (P1). A snapshot is {@code OPEN} while a deep scrape streams its
 * items in per scroll; it is {@code SEALED} once the pass completes, {@code CANCELLED} when a person stopped it, or
 * swept to {@code INTERRUPTED} when a client abandons an open pass past the stale TTL (P1 GC). A partial pass's
 * already-shipped items remain usable however it ended (take-what-we-get).
 *
 * <p><strong>CANCELLED and INTERRUPTED are the same data and different knowledge.</strong> Both hold a partial
 * pass, and the items in each are equally real. What separates them is who says so and when: a cancellation is
 * REPORTED by the machine at the moment a person stopped it, while an interruption is INFERRED by a sweeper six
 * hours after a client stopped talking. Folding the first into the second would mean an operator who pressed Stop
 * watching their own snapshot sit at OPEN for six hours and then be told the client had abandoned it — which is
 * not what happened, and is exactly the reading they would have to argue with later.
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
    /** A person stopped the scrape. Reported by the machine that was running it, not inferred later. */
    CANCELLED,
    INTERRUPTED,
    REJECTED
}
