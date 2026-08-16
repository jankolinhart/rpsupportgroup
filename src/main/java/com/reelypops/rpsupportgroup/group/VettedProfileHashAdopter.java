package com.reelypops.rpsupportgroup.group;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a newly-observed banner picture to the references it belongs to — <strong>by appending, never replacing</strong>.
 *
 * <p>A marker owner who re-screenshots or re-exports their banner changes nothing for the group's members, who read a
 * picture; but the perceptual hash moves and the vetted reference stops matching. The remedy is to teach the profile
 * the new picture, and {@code dHashes} is a list precisely so a reference can hold every version of its banner.</p>
 *
 * <p><strong>Why appending is not merely polite.</strong> Measured on `glowbloggeragency` (16/08/2026), keeping the
 * old hash alongside the new one also feeds the client's threshold calibration the evidence it was missing:</p>
 *
 * <pre>
 *   corrupt reference           threshold floor: 10
 *   repaired from stored image  threshold floor: 10
 *   repaired + ADDED live image threshold floor:  8
 * </pre>
 *
 * <p>With both pictures present the calibration can see that Sunday's START and the ENDE banner are only 9 bits
 * apart, and tightens its own floor. Replacing would have kept the floor at 10 and left the role misread possible.</p>
 *
 * <p>Pure and side-effect free: it returns a new profile rather than mutating the stored one, so the caller keeps the
 * existing save + snapshot-versioning path (and its change-note diff) unchanged.</p>
 */
final class VettedProfileHashAdopter {

    private VettedProfileHashAdopter() {
    }

    /**
     * A copy of {@code profile} in which every reference of {@code markerRole} also carries {@code newHash}.
     *
     * <p>Matched by ROLE only — kept for callers that genuinely have no text. Prefer
     * {@link #append(VettedProfile, String, String, String)}: on a per-weekday group this overload spreads one day's
     * picture across every reference sharing the role.</p>
     */
    static VettedProfile append(VettedProfile profile, String markerRole, String newHash) {
        return append(profile, markerRole, null, newHash);
    }

    /**
     * A copy of {@code profile} in which every reference matching {@code markerRole} <em>and</em> {@code markerText}
     * also carries {@code newHash}.
     *
     * <p><strong>Why the text matters.</strong> A per-weekday group carries several different banners for one role:
     * `glowbloggeragency` has a generic weekday START ("G B AGENCY START") and a distinct Sunday handoff START
     * ("GB AGENCY START Sonntag"), and the six weekday STARTs are 0 bits apart while Sunday's is 30 bits from them.
     * Matching on role alone would add Sunday's picture to Monday's reference, teaching Monday to match a banner it
     * will never legitimately see — a false-positive generator, and a widening of exactly the separation the
     * calibration depends on. When {@code markerText} is given, only the reference carrying that text is taught.</p>
     *
     * <p>A blank text falls back to role-only matching, so a client that cannot report the text still gets the old
     * behaviour rather than nothing.</p>
     *
     * <p>A reference that already holds the hash is left exactly as it is, so adopting twice is harmless — an
     * administrator clicking the button again, or two clients reporting the same drift, must not accumulate
     * duplicates.</p>
     *
     * <p>A blank role touches nothing: without knowing which reference the picture belongs to, adding it anywhere
     * would be a guess, and a wrong hash on a reference is worse than a missing one.</p>
     */
    static VettedProfile append(VettedProfile profile, String markerRole, String markerText, String newHash) {
        if (profile == null || newHash == null || newHash.isBlank() || markerRole == null || markerRole.isBlank()) {
            return profile;
        }
        VettedProfile.DetectorArtifacts detector = profile.detector() == null ? null
                : new VettedProfile.DetectorArtifacts(profile.detector().style(),
                        appendToAll(profile.detector().references(), markerRole, markerText, newHash));

        WeeklyScheduleDefinition weekly = profile.weeklySchedule() == null
                || profile.weeklySchedule().days() == null ? profile.weeklySchedule()
                : new WeeklyScheduleDefinition(profile.weeklySchedule().days().stream()
                        .map(d -> d == null || d.references() == null ? d
                                : new DayDefinition(d.weekday(), d.open(), d.type(), d.startMarkerTime(),
                                        d.endMarkerTime(), d.endMarkerDayOffset(), d.startMarkerDayOffset(),
                                        d.singleMarkerTime(), d.likesUntilTime(), d.likesUntilDayOffset(),
                                        d.tagRemoveEarliestTime(), d.tagRemoveEarliestDayOffset(),
                                        d.maxTaggedPosts(), d.style(),
                                        appendToAll(d.references(), markerRole, markerText, newHash)))
                        .toList());

        return new VettedProfile(profile.definition(), detector, profile.description(), weekly);
    }

    private static List<VettedProfile.TypedMarkerReference> appendToAll(
            List<VettedProfile.TypedMarkerReference> refs, String markerRole, String markerText, String newHash) {
        if (refs == null) {
            return null;
        }
        return refs.stream().map(r -> {
            if (r == null || !markerRole.equalsIgnoreCase(r.markerType()) || !textMatches(markerText, r.ocrText())) {
                return r;
            }
            List<String> hashes = r.dHashes() == null ? List.of() : r.dHashes();
            if (hashes.contains(newHash)) {
                return r; // already known — adopting twice must not accumulate duplicates
            }
            List<String> grown = new ArrayList<>(hashes);
            grown.add(newHash);
            return new VettedProfile.TypedMarkerReference(r.markerType(), List.copyOf(grown), r.ocrText(),
                    r.matchThreshold(), r.source(), r.shortcode(), r.imageUrl(), r.imageLocator());
        }).toList();
    }

    /**
     * Does this reference carry the text the drift named? A blank wanted-text matches everything (role-only
     * fallback); otherwise the comparison ignores case and surrounding whitespace, because the text travels through
     * OCR on the client and an exact-bytes match would be brittle for no benefit.
     */
    private static boolean textMatches(String wanted, String referenceText) {
        if (wanted == null || wanted.isBlank()) {
            return true;
        }
        return referenceText != null && wanted.trim().equalsIgnoreCase(referenceText.trim());
    }
}
