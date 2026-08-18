package com.reelypops.rpsupportgroup.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Repairs a vetted profile that carries a dHash which is <strong>not a hash</strong>, by <strong>copying a
 * fingerprint a CLIENT computed</strong> for the same picture.
 *
 * <p><strong>The fault this exists for.</strong> On 15/08/2026 `glowbloggeragency`'s Sunday START reference held a
 * 39-character Instagram post shortcode where its hash belongs. Every comparison site skips a hash whose length
 * differs from the candidate's, so the reference was INVISIBLE — it could not match, could not contradict, and never
 * took part in the threshold calibration that sets the profile's width. Ingest validation now refuses to accept one,
 * but a profile stored BEFORE that guard keeps its fault until someone repairs it — and with the guard live, such a
 * group cannot be saved through the portal at all, so it needs a repair path that does not go through the editor.</p>
 *
 * <p><strong>⚠️ It does not compute a hash, and must never start.</strong> Until 18/08/2026 it recomputed one here
 * with {@code ImageDHash}. That was wrong in a way nothing could see: measured on real Instagram bytes, this
 * service's Java hasher (ImageIO/AWT) and the desktop client's {@code lib/dhash.js} (sharp/libvips) land
 * <strong>15–34 bits apart</strong> — the distance between unrelated images — while clients accept a match at
 * 4–10. A hash minted here is therefore well-formed, plausible, stored, shipped to every client, and unmatchable
 * by all of them. Cross-PLATFORM agreement is proven (0 bits on ubuntu/windows/macos with real Instagram
 * fixtures); it is crossing IMPLEMENTATIONS that breaks. The client's value is the only currency.</p>
 *
 * <p><strong>No Instagram access is involved</strong> (directive B1). The fingerprint already exists: a deep scrape
 * streamed one for this very post, and clients deliver one with every live picture they report.</p>
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
     * Replace every malformed hash with a client-computed one.
     *
     * @param profile     the profile to repair
     * @param clientHashOf resolves a reference to a fingerprint a CLIENT computed for it (by post, or from a live
     *                     picture a client delivered), empty when none is on record
     * @param unrepairable collects a human-readable line per reference that could NOT be repaired, so the caller can
     *                     say what stood in the way instead of reporting a silent no-op
     * @return a repaired copy, or {@code profile} itself when nothing changed
     */
    static VettedProfile repair(VettedProfile profile, Function<VettedProfile.TypedMarkerReference, Optional<String>> clientHashOf,
                                List<String> unrepairable) {
        if (profile == null) {
            return null;
        }
        boolean[] changed = {false};

        VettedProfile.DetectorArtifacts detector = profile.detector() == null ? null
                : new VettedProfile.DetectorArtifacts(profile.detector().style(),
                        repairAll(profile.detector().references(), clientHashOf, unrepairable, changed));

        WeeklyScheduleDefinition weekly = profile.weeklySchedule() == null
                || profile.weeklySchedule().days() == null ? profile.weeklySchedule()
                : new WeeklyScheduleDefinition(profile.weeklySchedule().days().stream()
                        .map(d -> d == null || d.references() == null ? d
                                : new DayDefinition(d.weekday(), d.open(), d.type(), d.startMarkerTime(),
                                        d.endMarkerTime(), d.endMarkerDayOffset(), d.startMarkerDayOffset(),
                                        d.singleMarkerTime(), d.likesUntilTime(), d.likesUntilDayOffset(),
                                        d.tagRemoveEarliestTime(), d.tagRemoveEarliestDayOffset(),
                                        d.maxTaggedPosts(), d.style(),
                                        repairAll(d.references(), clientHashOf, unrepairable, changed)))
                        .toList());

        return changed[0] ? new VettedProfile(profile.definition(), detector, profile.description(), weekly) : profile;
    }

    private static List<VettedProfile.TypedMarkerReference> repairAll(
            List<VettedProfile.TypedMarkerReference> refs, Function<VettedProfile.TypedMarkerReference, Optional<String>> clientHashOf,
            List<String> unrepairable, boolean[] changed) {
        if (refs == null) {
            return null;
        }
        return refs.stream().map(r -> repairOne(r, clientHashOf, unrepairable, changed)).toList();
    }

    private static VettedProfile.TypedMarkerReference repairOne(
            VettedProfile.TypedMarkerReference r, Function<VettedProfile.TypedMarkerReference, Optional<String>> clientHashOf,
            List<String> unrepairable, boolean[] changed) {
        if (r == null || r.dHashes() == null) {
            return r;
        }
        List<String> good = r.dHashes().stream().filter(VettedProfileHashRepairer::wellFormed).toList();
        if (good.size() == r.dHashes().size()) {
            return r; // nothing malformed on this reference
        }
        Optional<String> fromClient = clientHashOf.apply(r);
        if (fromClient.isEmpty()) {
            // Leave it exactly as it is, and SAY SO. An emptied reference would look repaired while still matching
            // nothing; a hash minted here would look repaired while matching nothing — and neither would ever be
            // questioned again. An honest "this needs a re-vet" is the only outcome that stays true.
            unrepairable.add(describe(r) + ": no client has ever reported a fingerprint for this picture"
                    + " (its scrape pass is pruned or was voided, and no live picture has been delivered)"
                    + " — this reference needs a re-vet");
            return r;
        }
        List<String> repaired = new ArrayList<>(good);
        if (!repaired.contains(fromClient.get())) {
            repaired.add(fromClient.get());
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
