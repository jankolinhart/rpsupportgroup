package com.reelypops.rpsupportgroup.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Repairs a vetted profile that carries a dHash which is <strong>not a hash</strong>, by recomputing it from the
 * reference's OWN stored picture.
 *
 * <p><strong>The fault this exists for.</strong> On 15/08/2026 `glowbloggeragency`'s Sunday START reference held a
 * 39-character Instagram post shortcode where its hash belongs. Every comparison site skips a hash whose length
 * differs from the candidate's, so the reference was INVISIBLE — it could not match, could not contradict, and never
 * took part in the threshold calibration that sets the profile's width. Ingest validation now refuses to accept one,
 * but a profile stored BEFORE that guard keeps its fault until someone repairs it — and with the guard live, such a
 * group cannot be saved through the portal at all, so it needs a repair path that does not go through the editor.</p>
 *
 * <p><strong>No Instagram access is involved</strong> (directive B1). The reference's picture is already stored
 * against its {@code imageLocator} — the hash that should have been written is recomputable from bytes we hold.</p>
 *
 * <p><strong>Replace, don't append — the one place in this loop where that is right.</strong> Adoption appends,
 * because an older picture is still a real picture and keeping both feeds the client's calibration. A malformed
 * value is not evidence of anything: it matches nothing, contradicts nothing, and distorts calibration precisely by
 * being unmeasurable. So it is dropped, and a well-formed hash recomputed from the picture takes its place. Any
 * well-formed hashes already on the reference are kept, in their original order.</p>
 *
 * <p>Pure and side-effect free: returns a new profile, or the SAME instance when there was nothing to repair, so the
 * caller can tell "repaired" from "no-op" by identity.</p>
 */
final class VettedProfileHashRepairer {

    /** Same test the ingest boundary applies — a usable hash is exactly 64 binary digits. */
    private static final Pattern WELL_FORMED = Pattern.compile("^[01]{64}$");

    private VettedProfileHashRepairer() {
    }

    /**
     * Recompute every malformed hash from its reference's stored picture.
     *
     * @param profile     the profile to repair
     * @param hashOfImage resolves an {@code imageLocator} to the dHash of the stored picture, empty when unavailable
     * @param unrepairable collects a human-readable line per reference that could NOT be repaired, so the caller can
     *                     say what stood in the way instead of reporting a silent no-op
     * @return a repaired copy, or {@code profile} itself when nothing changed
     */
    static VettedProfile repair(VettedProfile profile, Function<String, Optional<String>> hashOfImage,
                                List<String> unrepairable) {
        if (profile == null) {
            return null;
        }
        boolean[] changed = {false};

        VettedProfile.DetectorArtifacts detector = profile.detector() == null ? null
                : new VettedProfile.DetectorArtifacts(profile.detector().style(),
                        repairAll(profile.detector().references(), hashOfImage, unrepairable, changed));

        WeeklyScheduleDefinition weekly = profile.weeklySchedule() == null
                || profile.weeklySchedule().days() == null ? profile.weeklySchedule()
                : new WeeklyScheduleDefinition(profile.weeklySchedule().days().stream()
                        .map(d -> d == null || d.references() == null ? d
                                : new DayDefinition(d.weekday(), d.open(), d.type(), d.startMarkerTime(),
                                        d.endMarkerTime(), d.endMarkerDayOffset(), d.startMarkerDayOffset(),
                                        d.singleMarkerTime(), d.likesUntilTime(), d.likesUntilDayOffset(),
                                        d.tagRemoveEarliestTime(), d.tagRemoveEarliestDayOffset(),
                                        d.maxTaggedPosts(), d.style(),
                                        repairAll(d.references(), hashOfImage, unrepairable, changed)))
                        .toList());

        return changed[0] ? new VettedProfile(profile.definition(), detector, profile.description(), weekly) : profile;
    }

    private static List<VettedProfile.TypedMarkerReference> repairAll(
            List<VettedProfile.TypedMarkerReference> refs, Function<String, Optional<String>> hashOfImage,
            List<String> unrepairable, boolean[] changed) {
        if (refs == null) {
            return null;
        }
        return refs.stream().map(r -> repairOne(r, hashOfImage, unrepairable, changed)).toList();
    }

    private static VettedProfile.TypedMarkerReference repairOne(
            VettedProfile.TypedMarkerReference r, Function<String, Optional<String>> hashOfImage,
            List<String> unrepairable, boolean[] changed) {
        if (r == null || r.dHashes() == null) {
            return r;
        }
        List<String> good = r.dHashes().stream().filter(VettedProfileHashRepairer::wellFormed).toList();
        if (good.size() == r.dHashes().size()) {
            return r; // nothing malformed on this reference
        }
        Optional<String> recomputed = hashOfImage.apply(r.imageLocator());
        if (recomputed.isEmpty()) {
            // Leave it exactly as it is. An emptied reference would LOOK repaired while still matching nothing,
            // and the administrator would never learn that the picture behind it is gone.
            unrepairable.add(describe(r) + ": its own picture is not stored, so the hash cannot be recomputed"
                    + " — this reference needs a re-vet");
            return r;
        }
        List<String> repaired = new ArrayList<>(good);
        if (!repaired.contains(recomputed.get())) {
            repaired.add(recomputed.get());
        }
        changed[0] = true;
        return new VettedProfile.TypedMarkerReference(r.markerType(), List.copyOf(repaired), r.ocrText(),
                r.matchThreshold(), r.source(), r.shortcode(), r.imageUrl(), r.imageLocator());
    }

    private static boolean wellFormed(String hash) {
        return hash != null && WELL_FORMED.matcher(hash).matches();
    }

    /** Name a reference the way an administrator sees it, not by index. */
    private static String describe(VettedProfile.TypedMarkerReference r) {
        String role = r.markerType() == null ? "?" : r.markerType();
        return r.ocrText() == null || r.ocrText().isBlank() ? role : role + " \"" + r.ocrText() + "\"";
    }
}
