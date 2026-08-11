package com.reelypops.rpsupportgroup.group;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * The persisted, <strong>admin-only vetting advisory</strong> (M3a) — everything Tier&nbsp;0 / AI <em>detected</em> about
 * a group, each facet carrying its own confidence. It is an immutable snapshot the admin's Vetting Portal pre-fills from
 * and diffs against; it <strong>never ships to clients</strong> (only the admin-confirmed vettedProfile does). Stored as
 * jsonb on {@link SupportGroupConfig#getDetectedProfile()} so it evolves without a migration.
 *
 * <p>M3a fills the two facets Tier&nbsp;0 can measure for a flat-banner group — {@link #imageStyle} and {@link #owner} —
 * plus the untyped {@link #references}. M3b adds the Tier-0 owner {@link #candidates} (the per-owner contribution
 * breakdown of the discovered owner set) and the derived {@link #schedule} (group type, round timing, opening days,
 * current state) — all additive on the jsonb column. See {@code marker-auto-discovery.md} §3.5 / §4.1 / §6.
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
 * @param candidates    the discovered owner set as a per-owner contribution breakdown (who owns markers + their duty share)
 * @param schedule      the derived group type + round timing + opening days + current state (M3b); never {@code null}
 * @param aiDiscovery   the AI tier's verdict (M4), or {@code null} until an admin runs "Run AI discovery"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        ScheduleFacet schedule,
        AiDiscovery aiDiscovery) {

    /** The inferred marker style + how confident vetting is in it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StyleFacet(MarkerStyle value, double confidence) {
    }

    /** The candidate marker-owner roster + the owner-separation confidence. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OwnerFacet(List<String> roster, double confidence) {
    }

    /**
     * One recurring-image cluster, untyped until the admin assigns its marker type (start/end/single).
     * {@code dominantAuthor} is the account that posts this banner the most — its label in the advisory (a shared
     * banner's co-owners are in the {@link OwnerCandidate} breakdown, not here). {@code confidence} is how strongly this
     * cluster reads as a real marker (its normalised marker-signature strength, 0..1).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarkerReference(String dHash, int distinctPosts, String dominantAuthor, List<String> sampleShortcodes,
                                  double confidence) {
    }

    /**
     * One marker-owner candidate in the discovered owner SET (M6, [DECIDED 5.8]) — a per-OWNER contribution breakdown,
     * NOT a per-cluster row. A shared-banner duty rota surfaces EVERY co-owner here (each a repeat contributor of the
     * accepted marker clusters), ranked by how many marker posts they carry — so co-owners are no longer hidden inside
     * another author's cluster and coincidental sub-strength lookalike clusters never appear. {@code markerPosts} is the
     * owner's distinct marker posts across the accepted clusters, {@code markerClusters} how many distinct banners they
     * recur on, and {@code postShare} their fraction of the owner set's marker posts (the duty split, 0..1).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OwnerCandidate(String author, int markerPosts, int markerClusters, double postShare) {
    }

    /**
     * The derived schedule advisory (M3b + round-3 refinement): the group type (with its {@code symmetry} + pairing
     * sub-metrics), the round start/end (or single) times, the end-marker day offset (round-open duration in whole
     * days; 0 when the round opens + closes on the same day) with its own {@code endMarkerDayOffsetConfidence} (how
     * consistent that offset is across rounds), the <strong>open-span</strong> weekdays (every weekday a round is
     * active START→END, so a round that spans a weekend marks Sat+Sun open even with no marker posted then), the
     * {@code roundCount} reconstructed, and the current round state. {@code openingDaysConfidence} = how confident we
     * are that the discovered weekday set is correct = the fraction of those open days on which a marker was directly
     * observed (1.0 when every open day carries a boundary marker; lower when some open days are only inferred as
     * mid-round). Times are tz-agnostic time-of-day in <strong>UTC</strong> until the admin sets the group timezone in
     * the Vetting Dashboard. {@code maxTaggedPosts} is the deterministically-counted per-member cap (the median of each
     * round's max non-marker posts by any one member, directive P4) with {@code maxTaggedPostsConfidence} = how cleanly
     * that cap holds across rounds; {@code null}/0 when no round could be bounded. Any facet may be
     * empty/{@code UNKNOWN}/{@code null}/0 when the corpus does not support it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduleFacet(MarkerGroupType groupType, double groupTypeConfidence,
                                RoundTime start, RoundTime end, RoundTime single,
                                List<Integer> openWeekdays, double openingDaysConfidence,
                                RoundState currentState, Integer endMarkerDayOffset, double endMarkerDayOffsetConfidence,
                                double symmetry, double pairing, int roundCount,
                                Integer maxTaggedPosts, double maxTaggedPostsConfidence) {

        /** The derived round CLASS (M5 A3): CROSS_DAY when a TWO_MARKER round's end lands a day+ later; null off TWO_MARKER. */
        @JsonProperty("roundClass")
        public RoundClass roundClass() {
            return RoundClass.of(groupType == MarkerGroupType.TWO_MARKER, endMarkerDayOffset);
        }
    }

    /** A derived round-boundary time-of-day ({@code HH:mm}, UTC) + how tight (confident) the observed times were. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoundTime(String timeOfDayUtc, double confidence) {
    }

    /**
     * The AI tier's advisory verdict (M4, {@code marker-auto-discovery.md} §5) — the judgment Tier&nbsp;0 cannot make
     * for a text-overlay group: the marker {@link #style}, the round {@link #markerType}, the {@link #owner} (primary) +
     * the {@link #owners} SET, the typed
     * {@link #references} (with OCR target text for text-overlay), an overall {@link #ocrTargetText}, a
     * {@link #confidence}, a short {@link #reasoning}, and the per-weekday {@link WeeklySchedule} (M4.5). Produced only
     * on the explicit admin "Run AI discovery" action; {@code null} until then. Advisory only — it pre-fills the Vetting
     * Portal, never auto-vets. The {@link #passes} list is the run HISTORY (the metrics pass + any refinement passes,
     * each with its own timestamp + spend) so the discovery-progress UI survives a reload — not only the last {@link #usage}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiDiscovery(MarkerStyle style, String markerType, String owner, List<String> owners,
                              List<AiReference> references, String ocrTargetText, double confidence, String reasoning,
                              long generatedAtMs, WeeklySchedule weeklySchedule, Usage usage, List<AiPass> passes) {

        /**
         * The provider token spend + ESTIMATED cost of the MOST RECENT AI pass on this advisory (metrics pass, or the
         * latest refinement pass) — surfaced so the admin discovery progress UI can show a per-pass and convergence
         * cost. {@code costEstimate} is a configured-price estimate (a plain decimal string in {@code currency}), not a
         * billed figure; {@code null} on advisories produced before cost surfacing.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Usage(String model, long promptTokens, long completionTokens, String costEstimate,
                            String currency) {
        }

        /**
         * One recorded AI pass in this advisory's RUN HISTORY — a {@code METRICS} pass or a {@code REFINE} pass — so the
         * admin discovery-progress UI can show the FULL last-run breakdown (per-pass timestamp + token spend + how many
         * markers it added + whether it converged) after a reload, not only the live in-session progress. {@code kind} is
         * {@code METRICS} or {@code REFINE}; {@code ranAtMs} is epoch millis; {@code markersAdded} is the distinct markers
         * this pass contributed (the metrics pass's whole gallery, or a refine pass's NEW templates); {@code converged} is
         * true when a refinement pass added none (a metrics pass is always {@code false}).
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AiPass(String kind, long ranAtMs, String model, long promptTokens, long completionTokens,
                             String costEstimate, String currency, int markersAdded, boolean converged) {
        }

        /**
         * One AI-confirmed marker reference: its round slot ({@code start}/{@code end}/{@code single}), OCR text, the
         * representative {@code shortcode} of the cited cluster (the marker VARIATION's image; null when none), and a
         * per-marker {@code confidence} (0..1) — set for a GROUNDED per-weekday marker (recurrence consistency), null
         * for the flat gallery references (which carry only the advisory's overall confidence).
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AiReference(String markerType, String ocrText, String shortcode, Double confidence) {
        }

        /**
         * The AI's <strong>per-weekday</strong> schedule verdict (M4.5, vision §5c) — the canonical model where each
         * weekday is OPEN with its own round shape or CLOSED. {@code null} until a text-overlay-aware AI run fills it;
         * advisory, pre-fills the Vetting Portal per weekday. Times are {@code HH:mm} in {@code timezone} (UTC in v1
         * until the admin sets the group timezone).
         *
         * @param timezone the zone the day times are expressed in (UTC in v1)
         * @param days     one entry per weekday the model reported (MON…SUN)
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record WeeklySchedule(String timezone, List<DaySchedule> days) {

            /**
             * One weekday's schedule. {@code groupType} + {@code style} are the AI's raw strings (kept untyped so a
             * {@code CONTINUOUS} day and any novel value survive fail-open). When {@code open} is false the day is
             * CLOSED and the remaining fields are ignored.
             *
             * @param weekday            the weekday, {@code MON}…{@code SUN}
             * @param open               true when a round runs that day; false = CLOSED
             * @param groupType          the day's round structure (SINGLE_MARKER / TWO_MARKER / CONTINUOUS), or null
             * @param style              the day's marker style (FLAT_BANNER / TEXT_OVERLAY), or null
             * @param markers            the day's marker references (start / end / single) with OCR text
             * @param start              the round start time-of-day ({@code HH:mm}), or null
             * @param end                the round end time-of-day ({@code HH:mm}), or null
             * @param endMarkerDayOffset whole days from the start marker to the end marker (0 = same day), or null
             * @param startMarkerDayOffset whole days the start marker is posted before the round's IDENTITY day (M5
             *                           switcher P4): null/0 = same day, -1 = the previous evening (a handoff), or null
             * @param maxTaggedPosts     the per-member cap (max non-marker posts per round), or null
             * @param confidence         0..1 — the model's confidence in this day, or null
             */
            @JsonIgnoreProperties(ignoreUnknown = true)
            public record DaySchedule(String weekday, boolean open, String groupType, String style,
                                      List<AiReference> markers, String start, String end,
                                      Integer endMarkerDayOffset, Integer startMarkerDayOffset,
                                      Integer maxTaggedPosts, Double confidence) {

                /** The derived round CLASS (M5 A3) for this open TWO_MARKER weekday; null when CLOSED or not TWO_MARKER. */
                @JsonProperty("roundClass")
                public RoundClass roundClass() {
                    return open ? RoundClass.of("TWO_MARKER".equals(groupType), endMarkerDayOffset) : null;
                }
            }
        }
    }
}
