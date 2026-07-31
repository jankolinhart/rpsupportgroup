package com.reelypops.rpsupportgroup.group;

/**
 * The operational mode of a support-group config (M5 A2) — the soft kill-switch lever the operator can flip
 * independently of a re-vet. Adopted by clients via the {@code version} ETag poll (#4), so a change bumps the version.
 *
 * <ul>
 *   <li>{@link #LIKING} — normal operation (auto-liking runs). The default.</li>
 *   <li>{@link #SCRAPE_ONLY} — clients keep scraping/observing but do NOT auto-like (a soft pause).</li>
 *   <li>{@link #PAUSED} — clients halt automation for this group entirely.</li>
 * </ul>
 */
public enum SgConfigMode {
    LIKING,
    SCRAPE_ONLY,
    PAUSED
}
