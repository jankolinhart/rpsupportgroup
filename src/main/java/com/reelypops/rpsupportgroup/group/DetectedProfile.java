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
 * plus the untyped {@link #references}. M3b adds the ranked Tier-0 {@link #candidates} (the marker-signature metrics
 * behind the decision) and the derived {@link #schedule} (group type, round timing, opening days, current state) — all
 * additive on the jsonb column. See {@code marker-auto-discovery.md} §3.5 / §4.1 / §6.
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
 * @param candidates    the ranked Tier-0 marker-owner candidates + their signature metrics (why each did/didn't win)
 * @param schedule      the derived group type + round timing + opening days + current state (M3b); never {@code null}
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
        List<MarkerReference> references,
        List<OwnerCandidate> candidates,
        ScheduleFacet schedule) {

    /** The inferred marker style + how confident vetting is in it. */
    public record StyleFacet(MarkerStyle value, double confidence) {
    }

    /** The candidate marker-owner roster + the owner-separation confidence. */
    public record OwnerFacet(List<String> roster, double confidence) {
    }

    /**
     * One recurring-image cluster, untyped until the admin assigns its marker type (start/end/single). {@code confidence}
     * is how strongly this cluster reads as a real marker (its normalised marker-signature strength, 0..1).
     */
    public record MarkerReference(String dHash, int distinctPosts, List<String> sampleShortcodes, double confidence) {
    }

    /**
     * One ranked Tier-0 marker-owner candidate with the marker-signature metrics behind the gate (the numbers the
     * VETTING_DIAG log carried, now surfaced for the admin): how many distinct posts recur, how pure the ownership is,
     * how regular the cadence, how much of the grid it spans, and the resulting score.
     */
    public record OwnerCandidate(String author, int distinctPosts, int recurrence, double purity,
                                 double cadenceRegularity, double coverage, double score) {
    }

    /**
     * The derived schedule advisory (M3b): the group type, the round start/end (or single) times, the opening weekdays,
     * and the current round state. Times are tz-agnostic time-of-day in <strong>UTC</strong> until the admin sets the
     * group timezone in the Vetting Portal, then rendered group-local. Any facet may be empty/{@code UNKNOWN} with a 0
     * confidence when the corpus does not support it (too few dated marker posts, no clean owner, etc.).
     */
    public record ScheduleFacet(MarkerGroupType groupType, double groupTypeConfidence,
                                RoundTime start, RoundTime end, RoundTime single,
                                List<Integer> openWeekdays, double openingDaysConfidence,
                                RoundState currentState) {
    }

    /** A derived round-boundary time-of-day ({@code HH:mm}, UTC) + how tight (confident) the observed times were. */
    public record RoundTime(String timeOfDayUtc, double confidence) {
    }
}
