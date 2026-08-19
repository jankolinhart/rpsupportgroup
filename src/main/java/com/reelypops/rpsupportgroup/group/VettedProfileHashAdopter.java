package com.reelypops.rpsupportgroup.group;

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

    /**
     * Provenance for a reference whose display image came from a CLIENT's live capture rather than the corpus.
     *
     * <p>Load-bearing, not cosmetic: {@link MarkerImageEnricher} skips these, so its corpus-derived locator cannot
     * overwrite the adopted picture on the way through the save path.</p>
     */
    static final String SOURCE_CLIENT_DRIFT = "client-drift";

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
     * <p>A reference that already holds exactly this hash is left as it is, so adopting twice is a no-op — an
     * administrator clicking the button again, or two clients reporting the same drift, changes nothing.</p>
     *
     * <p>A reference is identifiable by ROLE, by TEXT, or by both — but at least one is required. With neither,
     * adding the picture anywhere would be a guess, and a wrong hash on a reference is worse than a missing one.</p>
     */
    static VettedProfile append(VettedProfile profile, String markerRole, String markerText, String newHash) {
        return append(profile, markerRole, markerText, newHash, null);
    }

    /**
     * As above, and — when {@code newImageLocator} is given — also repoints the reference's DISPLAY image at the
     * newly adopted picture.
     *
     * <p><strong>⚠️ ONE PICTURE, THE NEWEST. Adoption REPLACES; it does not accumulate.</strong> This reversed on
     * 18/08/2026 and the reversal is the user's, from observation: carrying a second, near-identical fingerprint
     * measurably <em>degrades</em> the client's threshold calibration. Calibration sets a reference's tolerance
     * from how far apart the roles sit, so two renditions of the same banner compress that separation and force a
     * tighter floor — on `glowbloggeragency` a duplicated START moved the signal enough to be visible immediately,
     * and removing it restored it. More fingerprints is NOT more recall; past a point it is less.</p>
     *
     * <p><strong>Known trade-off, accepted:</strong> a post still carrying the superseded banner stops matching.
     * That is the intended exchange — the marker that has to be recognised is the one being posted now.</p>
     *
     * <p>{@code imageLocator} follows the same picture, so the fingerprint and the image a human is shown always
     * describe the same bytes. Leaving it on the superseded picture meant an operator adopted the new banner and
     * then went on being shown the old one, which is precisely the confusion the re-vet prompt exists to
     * remove.</p>
     *
     * <p>The reference's {@code source} is stamped {@link #SOURCE_CLIENT_DRIFT} at the same time. That is not
     * decoration: {@link MarkerImageEnricher} re-derives {@code imageLocator} from the corpus representative for the
     * reference's shortcode on every save, and would otherwise overwrite this locator straight back to the old
     * picture on the way out. The source says "this picture came from a client's live capture, not the corpus",
     * and the enricher leaves it alone.</p>
     */
    static VettedProfile append(VettedProfile profile, String markerRole, String markerText, String newHash,
                                String newImageLocator) {
        return append(profile, null, markerRole, markerText, newHash, newImageLocator);
    }

    /**
     * As above, targeted at ONE weekday's slot when {@code targetWeekday} is given (19/08/2026).
     *
     * <p><strong>With a weekday, exactly that day's references change and nothing else does</strong> — not the
     * other days sharing the role+text, and not the detector block. The profile is per-weekday by design: an
     * owner may post a flower on Monday and a monkey on Tuesday, both reading "START", and a Tuesday drift says
     * nothing about Monday's banner. Weekday-blind adoption stamped all seven START slots with Wednesday's
     * picture (observed 18/08/2026) and set the report/adopt loop oscillating between two banners fighting over
     * one slot.</p>
     *
     * <p><strong>Without a weekday</strong> (a flat/legacy group, an old client, a corrupt-reference repair —
     * kinds that carry no day) the pre-weekday behaviour stands: every day sharing the role+text, and the
     * detector block with them. That path must survive unchanged, or legacy groups lose adoption entirely — their
     * references live nowhere else.</p>
     */
    static VettedProfile append(VettedProfile profile, Integer targetWeekday, String markerRole, String markerText,
                                String newHash, String newImageLocator) {
        boolean haveRole = markerRole != null && !markerRole.isBlank();
        boolean haveText = markerText != null && !markerText.isBlank();
        // A reference is identifiable by its ROLE, its TEXT, or both. Requiring the role made a missing one a
        // SILENT no-op — and that is exactly what happened live on 16/08: a field-name mismatch upstream delivered
        // a null role, adoption matched nothing, and a repair appeared to succeed while the live banner it was
        // meant to add was quietly dropped. Text alone identifies a reference precisely, so it is enough.
        if (profile == null || newHash == null || newHash.isBlank() || (!haveRole && !haveText)) {
            return profile;
        }
        VettedProfile.DetectorArtifacts detector = profile.detector() == null || targetWeekday != null
                ? profile.detector()
                : new VettedProfile.DetectorArtifacts(profile.detector().style(),
                        appendToAll(profile.detector().references(), markerRole, markerText, newHash,
                                newImageLocator));

        WeeklyScheduleDefinition weekly = profile.weeklySchedule() == null
                || profile.weeklySchedule().days() == null ? profile.weeklySchedule()
                : new WeeklyScheduleDefinition(profile.weeklySchedule().days().stream()
                        .map(d -> d == null || d.references() == null
                                || (targetWeekday != null && d.weekday() != targetWeekday) ? d
                                : new DayDefinition(d.weekday(), d.open(), d.type(), d.startMarkerTime(),
                                        d.endMarkerTime(), d.endMarkerDayOffset(), d.startMarkerDayOffset(),
                                        d.singleMarkerTime(), d.likesUntilTime(), d.likesUntilDayOffset(),
                                        d.tagRemoveEarliestTime(), d.tagRemoveEarliestDayOffset(),
                                        d.maxTaggedPosts(), d.style(),
                                        appendToAll(d.references(), markerRole, markerText, newHash,
                                                newImageLocator)))
                        .toList());

        return new VettedProfile(profile.definition(), detector, profile.description(), weekly);
    }

    private static List<VettedProfile.TypedMarkerReference> appendToAll(
            List<VettedProfile.TypedMarkerReference> refs, String markerRole, String markerText, String newHash,
            String newImageLocator) {
        if (refs == null) {
            return null;
        }
        return refs.stream().map(r -> {
            if (r == null || !roleMatches(markerRole, r.markerType()) || !textMatches(markerText, r.ocrText())) {
                return r;
            }
            List<String> stored = r.dHashes() == null ? List.of() : r.dHashes();
            boolean repointing = newImageLocator != null && !newImageLocator.isBlank()
                    && !newImageLocator.equals(r.imageLocator());
            if (stored.equals(List.of(newHash)) && !repointing) {
                return r; // already exactly this — adopting twice must be a no-op
            }
            // ⚠️ REPLACE. The reference ends up carrying the client's newest fingerprint and NOTHING else.
            //
            // This also CLEANS, without needing to check. A reference can be both corrupt and drifted — glow was
            // exactly that — and a list still holding a non-hash would be refused by the ingest boundary (400),
            // so the button would fail on the group that needed it most. Replacing the whole list drops any
            // malformed value with the rest, so there is no separate well-formedness filter to keep in step.
            List<String> adopted = List.of(newHash);
            // The display follows the picture the hash describes. `imageUrl` is dropped with it — it names the
            // SUPERSEDED image, and a stale URL beats no URL only if you never look at it.
            return new VettedProfile.TypedMarkerReference(r.markerType(), adopted, r.ocrText(),
                    r.matchThreshold(), repointing ? SOURCE_CLIENT_DRIFT : r.source(), r.shortcode(),
                    repointing ? null : r.imageUrl(), repointing ? newImageLocator : r.imageLocator());
        }).toList();
    }

    /**
     * Does this reference carry the text the drift named? A blank wanted-text matches everything (role-only
     * fallback); otherwise the comparison ignores case and surrounding whitespace, because the text travels through
     * OCR on the client and an exact-bytes match would be brittle for no benefit.
     */
    /** A blank wanted-role matches everything — the TEXT is then carrying the identification on its own. */
    private static boolean roleMatches(String wanted, String referenceRole) {
        return wanted == null || wanted.isBlank() || wanted.equalsIgnoreCase(referenceRole);
    }

    private static boolean textMatches(String wanted, String referenceText) {
        if (wanted == null || wanted.isBlank()) {
            return true;
        }
        return referenceText != null && wanted.trim().equalsIgnoreCase(referenceText.trim());
    }
}
