package com.reelypops.rpsupportgroup.group;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * The <strong>single authoritative confirmed</strong> SG profile (M3a) — the admin-corrected/completed output of the
 * Vetting Portal, and the object that <em>ships</em> to clients. It is the parallel of the advisory
 * {@link DetectedProfile} (same attributes, minus the per-facet confidence/provenance meta), so the Portal's living diff
 * is a straight field-by-field compare. Stored as jsonb on {@link SupportGroupConfig#getVettedProfile()}.
 *
 * <p><strong>Clean-cut model + temporary bridge (marker-auto-discovery.md §6).</strong> This is the one place round-truth
 * is edited. To keep today's client + scanner working, {@link SupportGroupConfig} keeps its legacy jsonb
 * {@code definition} column as a <em>one-way projection</em> of {@link #definition()} (regenerated on every save, never
 * edited independently — so it cannot diverge). <strong>M5 retires the projection</strong>: client + scanner rewire to
 * read {@code vetted_profile} directly and {@code definition} is dropped.
 *
 * @param definition     the confirmed round-truth (type, tz, times, opening days, marker owners) — projected to the
 *                       legacy {@code definition} column; required + validated. When {@code weeklySchedule} is present it
 *                       is <em>regenerated</em> from that (the first OPEN day) by {@link #withDerivedDefinition()}.
 * @param detector       the confirmed recognition detector (style + typed references); admin-only until M5 ships it
 * @param description    the confirmed group blurb — projected to the legacy {@code description} column
 * @param weeklySchedule the admin-confirmed <strong>per-weekday</strong> program (M4.5, vision §5c) — the authoritative
 *                       weekly truth M5 consumes; {@code null} for a legacy flat-only profile
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VettedProfile(
        @NotNull @Valid GroupDefinition definition,
        DetectorArtifacts detector,
        String description,
        WeeklyScheduleDefinition weeklySchedule) {

    /**
     * The pre-M5 projection (M4.5-e, D1): when a per-weekday {@link #weeklySchedule} is present, return a copy whose flat
     * {@link #definition} + {@link #detector} are regenerated from the <strong>first OPEN day</strong> (its schedule
     * flattened onto the group-global type / timezone / owners, with {@code openWeekdays} = the open days). Returns
     * {@code this} unchanged when there is no weekly schedule or no open day — so a legacy flat profile is untouched.
     */
    public VettedProfile withDerivedDefinition() {
        if (weeklySchedule == null) {
            return this;
        }
        return weeklySchedule.representativeDay()
                .map(rep -> new VettedProfile(rep.toDefinition(definition, weeklySchedule.openWeekdays()),
                        rep.toDetector(), description, weeklySchedule))
                .orElse(this);
    }

    /**
     * The confirmed recognition detector the client's runtime uses (auto-derived, admin-confirmed).
     *
     * @param style      the marker style (flat-banner vs text-overlay)
     * @param references the per-marker-type reference images the runtime matches against
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DetectorArtifacts(MarkerStyle style, List<TypedMarkerReference> references) {
    }

    /**
     * One confirmed marker reference, now <em>typed</em> — the admin has assigned which marker slot a detected cluster
     * belongs to (D1c).
     *
     * @param markerType     the assigned slot ({@code start} / {@code end} / {@code single})
     * @param dHashes        the reference perceptual hash(es) — canonical + per-weekday variants. For a
     *                       {@code TEXT_OVERLAY} style these are <em>crop</em> dHashes (the overlay region only); for a
     *                       {@code FLAT_BANNER} style the whole-image hashes (the style lives on {@link DetectorArtifacts})
     * @param ocrText        the confirmed OCR target text for this marker, or {@code null} — the primary signal for the
     *                       client's TEXT_OVERLAY two-pass verify (agenda&nbsp;#3), carried from AI discovery through the
     *                       Vetting Portal. Additive + nullable (jsonb-safe): legacy rows deserialize with {@code null}
     * @param matchThreshold the per-group Hamming match threshold for this reference
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TypedMarkerReference(String markerType, List<String> dHashes, String ocrText, int matchThreshold) {
    }
}
