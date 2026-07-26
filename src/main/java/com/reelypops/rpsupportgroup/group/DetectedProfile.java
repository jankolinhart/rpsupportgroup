package com.reelypops.rpsupportgroup.group;

import java.util.List;
import java.util.UUID;

/**
 * The persisted, <strong>admin-only vetting advisory</strong> (M3a) — everything Tier&nbsp;0 / AI <em>detected</em> about
 * a group, each facet carrying its own confidence. It is an immutable snapshot the admin's Vetting Portal pre-fills from
 * and diffs against; it <strong>never ships to clients</strong> (only the admin-confirmed vettedProfile does). Stored as
 * jsonb on {@link SupportGroupConfig#getDetectedProfile()} so it evolves without a migration.
 *
 * <p>M3a fills the two facets Tier&nbsp;0 can measure for a flat-banner group — {@link #imageStyle} and {@link #owner} —
 * plus the untyped {@link #references}. M3b will add group-type, round-timing and opening-day facets (all additive on the
 * jsonb column). See {@code marker-auto-discovery.md} §6.
 *
 * @param snapshotId    the sealed snapshot this advisory was derived from
 * @param igAccount     the support group's Instagram account
 * @param itemCount     how many grid items were considered
 * @param generatedAtMs epoch millis when this advisory was computed
 * @param provenance    which tier(s) produced it (e.g. {@code TIER_0_DHASH})
 * @param escalate      true when Tier&nbsp;0 could not decide (text-overlay) and vision escalation is warranted
 * @param imageStyle    the inferred marker style + its confidence
 * @param owner         the candidate marker-owner roster + its confidence
 * @param references    the recurring-image clusters (untyped — the admin assigns start/end/single at vet time, D1c)
 */
public record DetectedProfile(
        UUID snapshotId,
        String igAccount,
        int itemCount,
        long generatedAtMs,
        String provenance,
        boolean escalate,
        StyleFacet imageStyle,
        OwnerFacet owner,
        List<MarkerReference> references) {

    /** The inferred marker style + how confident vetting is in it. */
    public record StyleFacet(MarkerStyle value, double confidence) {
    }

    /** The candidate marker-owner roster + the owner-separation confidence. */
    public record OwnerFacet(List<String> roster, double confidence) {
    }

    /** One recurring-image cluster, untyped until the admin assigns its marker type (start/end/single). */
    public record MarkerReference(String dHash, int distinctPosts, List<String> sampleShortcodes) {
    }
}
