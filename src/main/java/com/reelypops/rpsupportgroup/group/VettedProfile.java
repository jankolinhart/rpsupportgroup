package com.reelypops.rpsupportgroup.group;

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
 * @param definition  the confirmed round-truth (type, tz, times, opening days, marker owners) — projected to the legacy
 *                    {@code definition} column; required + validated (a profile is not saveable without it)
 * @param detector    the confirmed recognition detector (style + typed references); admin-only until M5 ships it
 * @param description the confirmed group blurb — projected to the legacy {@code description} column
 */
public record VettedProfile(
        @NotNull @Valid GroupDefinition definition,
        DetectorArtifacts detector,
        String description) {

    /**
     * The confirmed recognition detector the client's runtime uses (auto-derived, admin-confirmed).
     *
     * @param style      the marker style (flat-banner vs text-overlay)
     * @param references the per-marker-type reference images the runtime matches against
     */
    public record DetectorArtifacts(MarkerStyle style, List<TypedMarkerReference> references) {
    }

    /**
     * One confirmed marker reference, now <em>typed</em> — the admin has assigned which marker slot a detected cluster
     * belongs to (D1c).
     *
     * @param markerType     the assigned slot ({@code start} / {@code end} / {@code single})
     * @param dHashes        the reference perceptual hash(es) — canonical + per-weekday variants
     * @param matchThreshold the per-group Hamming match threshold for this reference
     */
    public record TypedMarkerReference(String markerType, List<String> dHashes, int matchThreshold) {
    }
}
