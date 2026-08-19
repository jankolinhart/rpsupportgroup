package com.reelypops.rpsupportgroup.group;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adoption REPLACES a banner's picture with the newest one. A reference carries ONE fingerprint.
 *
 * <p><strong>⚠️ This reversed on 18/08/2026, and the reversal came from watching it happen.</strong> The earlier
 * design appended, on the reasoning that a second picture widens what can be recognised — it was even measured
 * once as dropping glow's calibrated floor from 10 to 8. Live use showed the opposite effect dominates: a
 * second, near-identical fingerprint of the same banner <em>degrades</em> the client's threshold calibration.
 * Calibration reads a reference's tolerance from how far apart the roles sit, so two renditions of one banner
 * compress that separation and force a tighter floor. On `glowbloggeragency` a duplicated START moved the signal
 * enough to be obvious immediately, and removing it by hand restored it.</p>
 *
 * <p><strong>Known trade-off, accepted:</strong> a post still carrying the superseded banner stops matching. That
 * is the intended exchange — the marker that has to be recognised is the one being posted now.</p>
 */
class VettedProfileHashAdopterTest {

    private static final String OLD = "1010".repeat(16);
    private static final String NEW = "1100".repeat(16);

    private static VettedProfile.TypedMarkerReference ref(String type, String text, String... hashes) {
        return new VettedProfile.TypedMarkerReference(type, Arrays.asList(hashes), text, 4, "detected", "SC", null, null);
    }

    private static GroupDefinition definition() {
        return new GroupDefinition(SgType.TWO_MARKER, "Europe/Paris", List.of("glowbloggeragency"), null, null,
                "20:31", "20:31", 0, null, null, null, null, null, null);
    }

    private static VettedProfile profile(List<VettedProfile.TypedMarkerReference> detector,
                                         List<VettedProfile.TypedMarkerReference> sunday) {
        DayDefinition day = new DayDefinition(0, true, SgType.TWO_MARKER, "20:31", "20:31", 0, -1, null,
                "23:59", 0, "10:30", 1, 1, MarkerStyle.TEXT_OVERLAY, sunday);
        return new VettedProfile(definition(),
                detector == null ? null : new VettedProfile.DetectorArtifacts(MarkerStyle.TEXT_OVERLAY, detector),
                "desc", new WeeklyScheduleDefinition(List.of(day)));
    }

    @Test
    void REPLACES_theOldPictureWithTheNewestOne() {
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(List.of(ref("start", "START Sonntag", OLD)), List.of(ref("start", "START Sonntag", OLD))),
                "start", NEW);

        assertThat(out.detector().references().get(0).dHashes()).containsExactly(NEW);
        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(NEW);
    }

    @Test
    void ACCEPTANCE_aWeekdayTargetedAdoptionWritesEXACTLY_thatDay_andNothingElse() {
        // THE 18/08/2026 FAULT, inverted into the acceptance criterion agreed with the user: "if we find a drift
        // on a Tuesday we should only stamp the vetted profile's Tuesday image/hash". Monday, Tuesday and
        // Wednesday all carry the SAME role and text — the exact condition under which weekday-blind adoption
        // stamped all of them with one day's banner. The detector block stays untouched too: it drives style
        // detection, not per-day matching.
        DayDefinition mon = new DayDefinition(1, true, SgType.TWO_MARKER, "20:31", "20:31", 0, -1, null,
                "23:59", 0, "10:30", 1, 1, MarkerStyle.TEXT_OVERLAY, List.of(ref("start", "START", OLD)));
        DayDefinition tue = new DayDefinition(2, true, SgType.TWO_MARKER, "20:31", "20:31", 0, -1, null,
                "23:59", 0, "10:30", 1, 1, MarkerStyle.TEXT_OVERLAY, List.of(ref("start", "START", OLD)));
        DayDefinition wed = new DayDefinition(3, true, SgType.TWO_MARKER, "20:31", "20:31", 0, -1, null,
                "23:59", 0, "10:30", 1, 1, MarkerStyle.TEXT_OVERLAY, List.of(ref("start", "START", OLD)));
        VettedProfile in = new VettedProfile(definition(),
                new VettedProfile.DetectorArtifacts(MarkerStyle.TEXT_OVERLAY, List.of(ref("start", "START", OLD))),
                "desc", new WeeklyScheduleDefinition(List.of(mon, tue, wed)));

        VettedProfile out = VettedProfileHashAdopter.append(in, 2, "start", "START", NEW, null);

        List<DayDefinition> days = out.weeklySchedule().days();
        assertThat(days.get(0).references().get(0).dHashes()).containsExactly(OLD); // Monday untouched
        assertThat(days.get(1).references().get(0).dHashes()).containsExactly(NEW); // Tuesday — and ONLY Tuesday
        assertThat(days.get(2).references().get(0).dHashes()).containsExactly(OLD); // Wednesday untouched
        assertThat(out.detector().references().get(0).dHashes()).containsExactly(OLD); // detector untouched
    }

    @Test
    void aNullWeekdayKeepsTheLegacyBehaviour_everyMatchingDayAndTheDetector() {
        // Flat/legacy groups and kinds that carry no day (a corrupt-reference repair) must keep working — their
        // references live in the detector block and every day slice, and a targeted write would strand them.
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(List.of(ref("start", "START Sonntag", OLD)), List.of(ref("start", "START Sonntag", OLD))),
                null, "start", "START Sonntag", NEW, null);

        assertThat(out.detector().references().get(0).dHashes()).containsExactly(NEW);
        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(NEW);
    }

    @Test
    void leavesOtherRolesAlone() {
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "START", OLD), ref("end", "ENDE", OLD))), "start", NEW);

        List<VettedProfile.TypedMarkerReference> refs = out.weeklySchedule().days().get(0).references();
        assertThat(refs.get(0).dHashes()).containsExactly(NEW);
        assertThat(refs.get(1).dHashes()).containsExactly(OLD); // the END banner did not change
    }

    @Test
    void adoptingTwiceIsANoOp() {
        // An administrator clicking again, or two clients reporting the same drift, must be harmless.
        VettedProfile once = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "START", OLD))), "start", NEW);
        VettedProfile twice = VettedProfileHashAdopter.append(once, "start", NEW);

        assertThat(twice.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(NEW);
    }

    @Test
    void aReferenceWithNoHashesGainsItsFirstOne() {
        // The text-only reference the portal now writes when no image cluster resolved — adoption is how it finally
        // gets a picture, without a duty scrape.
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "START Sonntag"))), "start", NEW);

        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(NEW);
    }

    @Test
    void roleMatchingIsCaseInsensitive() {
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("START", "START", OLD))), "start", NEW);
        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(NEW);
    }

    @Test
    void nothingIsTouchedWithoutARoleOrAHash() {
        // Without knowing WHICH reference the picture belongs to, adding it anywhere would be a guess — and a wrong
        // hash on a reference is worse than a missing one.
        VettedProfile in = profile(null, List.of(ref("start", "START", OLD)));

        assertThat(VettedProfileHashAdopter.append(in, null, NEW)).isSameAs(in);   // no role AND no text
        assertThat(VettedProfileHashAdopter.append(in, "  ", NEW)).isSameAs(in);
        assertThat(VettedProfileHashAdopter.append(in, "start", null)).isSameAs(in);
        assertThat(VettedProfileHashAdopter.append(in, "start", " ")).isSameAs(in);
        assertThat(VettedProfileHashAdopter.append(null, "start", NEW)).isNull();
    }

    @Test
    void toleratesAProfileWithNoDetectorNoScheduleOrEmptyDays() {
        VettedProfile bare = new VettedProfile(definition(), null, "desc", null);
        assertThat(VettedProfileHashAdopter.append(bare, "start", NEW).detector()).isNull();

        VettedProfile noDays = new VettedProfile(definition(), null, "desc", new WeeklyScheduleDefinition(null));
        assertThat(VettedProfileHashAdopter.append(noDays, "start", NEW).weeklySchedule().days()).isNull();

        DayDefinition dayWithoutRefs = new DayDefinition(1, false, null, null, null, 0, null, null, null, 0,
                null, 0, 0, null, null);
        VettedProfile closed = new VettedProfile(definition(),
                new VettedProfile.DetectorArtifacts(MarkerStyle.TEXT_OVERLAY, null), "desc",
                new WeeklyScheduleDefinition(Arrays.asList(dayWithoutRefs, null)));
        VettedProfile out = VettedProfileHashAdopter.append(closed, "start", NEW);
        assertThat(out.detector().references()).isNull();
        assertThat(out.weeklySchedule().days()).hasSize(2);
    }

    @Test
    void aNullReferenceInTheListIsSkipped() {
        DayDefinition day = new DayDefinition(0, true, SgType.TWO_MARKER, "20:31", "20:31", 0, null, null,
                "23:59", 0, "10:30", 1, 1, MarkerStyle.TEXT_OVERLAY,
                Arrays.asList(null, ref("start", "START", OLD)));
        VettedProfile out = VettedProfileHashAdopter.append(
                new VettedProfile(definition(), null, "desc", new WeeklyScheduleDefinition(List.of(day))),
                "start", NEW);

        List<VettedProfile.TypedMarkerReference> refs = out.weeklySchedule().days().get(0).references();
        assertThat(refs.get(0)).isNull();
        assertThat(refs.get(1).dHashes()).containsExactly(NEW);
    }

    @Test
    void REGRESSION_theTEXT_picksTheONE_referenceThePictureBelongsTo() {
        // `glowbloggeragency` carries TWO start banners: a generic weekday one and a distinct Sunday handoff one,
        // 30 bits apart. Matching on ROLE alone would teach Monday to match Sunday's banner — a false-positive
        // generator, and a widening of exactly the separation the client's calibration depends on.
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "G B AGENCY START", OLD),
                        ref("start", "GB AGENCY START Sonntag", OLD))),
                "start", "GB AGENCY START Sonntag", NEW);

        List<VettedProfile.TypedMarkerReference> refs = out.weeklySchedule().days().get(0).references();
        assertThat(refs.get(0).dHashes()).containsExactly(OLD);       // the weekday banner did NOT change
        assertThat(refs.get(1).dHashes()).containsExactly(NEW);  // Sunday's did
    }

    @Test
    void textMatchingIgnoresCaseAndSurroundingWhitespace() {
        // The text travels through OCR on the client; an exact-bytes match would be brittle for no benefit.
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "  GB AGENCY START Sonntag  ", OLD))),
                "start", "gb agency start sonntag", NEW);

        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(NEW);
    }

    @Test
    void aBlankTextFallsBackToRoleOnlyMatching() {
        // A client that cannot report the text still gets the old behaviour rather than nothing at all.
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "A", OLD), ref("start", "B", OLD))), "start", "  ", NEW);

        List<VettedProfile.TypedMarkerReference> refs = out.weeklySchedule().days().get(0).references();
        assertThat(refs.get(0).dHashes()).containsExactly(NEW);
        assertThat(refs.get(1).dHashes()).containsExactly(NEW);
    }

    @Test
    void aNamedTextThatMatchesNoReferenceChangesNothing() {
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "START Sonntag", OLD))), "start", "SOMETHING ELSE", NEW);

        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(OLD);
    }

    @Test
    void aNamedTextNeverMatchesAReferenceThatHasNoTextOfItsOwn() {
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", null, OLD))), "start", "START Sonntag", NEW);

        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(OLD);
    }

    // ── The DISPLAY image, not just the fingerprint (16/08/2026) ──────────────

    @Test
    void adoptingALSO_repointsTheDisplayImageAtTheNewPicture() {
        // Hashes accumulate; the display tracks current. Leaving imageLocator on the superseded picture meant an
        // operator adopted the new banner and went on being shown the old one — the exact confusion the prompt
        // exists to remove.
        VettedProfile.TypedMarkerReference before = new VettedProfile.TypedMarkerReference(
                "start", List.of(OLD), "START Sonntag", 4, "detected", "SC", "http://old.example/pic", "old-loc");

        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(before)), "start", "START Sonntag", NEW, "new-loc");

        VettedProfile.TypedMarkerReference after = out.weeklySchedule().days().get(0).references().get(0);
        assertThat(after.dHashes()).containsExactly(NEW);   // every version stays matchable
        assertThat(after.imageLocator()).isEqualTo("new-loc");   // what a human sees is today's banner
        assertThat(after.imageUrl()).isNull();                   // it named the SUPERSEDED image
        assertThat(after.ocrText()).isEqualTo("START Sonntag");  // the banner's identity did not change
        // Load-bearing: MarkerImageEnricher skips this source, so its corpus lookup cannot put the old picture back.
        assertThat(after.source()).isEqualTo(VettedProfileHashAdopter.SOURCE_CLIENT_DRIFT);
    }

    @Test
    void withNoLocatorTheDisplayIsLeftEXACTLY_asItWas() {
        // A drift that arrived without a picture still contributes its hash; it must not blank the display.
        VettedProfile.TypedMarkerReference before = new VettedProfile.TypedMarkerReference(
                "start", List.of(OLD), "START", 4, "detected", "SC", "http://old.example/pic", "old-loc");

        VettedProfile out = VettedProfileHashAdopter.append(profile(null, List.of(before)), "start", "START", NEW);

        VettedProfile.TypedMarkerReference after = out.weeklySchedule().days().get(0).references().get(0);
        assertThat(after.dHashes()).containsExactly(NEW);
        assertThat(after.imageLocator()).isEqualTo("old-loc");
        assertThat(after.imageUrl()).isEqualTo("http://old.example/pic");
        assertThat(after.source()).isEqualTo("detected");
    }

    @Test
    void adoptingTheSAME_pictureTwiceIsStillIdempotent() {
        VettedProfile once = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "START", OLD))), "start", "START", NEW, "new-loc");
        VettedProfile twice = VettedProfileHashAdopter.append(once, "start", "START", NEW, "new-loc");

        VettedProfile.TypedMarkerReference after = twice.weeklySchedule().days().get(0).references().get(0);
        assertThat(after.dHashes()).containsExactly(NEW);
        assertThat(after.imageLocator()).isEqualTo("new-loc");
    }

    @Test
    void aKNOWN_hashWithANEW_pictureStillRepointsTheDisplay() {
        // Two clients report the same drifted banner: the second delivers a picture the first could not retain.
        // The hash is already known, but the display still has nothing to show — so this must not early-return.
        VettedProfile in = profile(null, List.of(new VettedProfile.TypedMarkerReference(
                "start", List.of(OLD, NEW), "START", 4, "detected", "SC", null, null)));

        VettedProfile out = VettedProfileHashAdopter.append(in, "start", "START", NEW, "new-loc");

        VettedProfile.TypedMarkerReference after = out.weeklySchedule().days().get(0).references().get(0);
        assertThat(after.dHashes()).containsExactly(NEW); // no duplicate
        assertThat(after.imageLocator()).isEqualTo("new-loc");
    }

    @Test
    void ADOPTING_dropsAMalformedValueInsteadOfCarryingItAlong() {
        // glow is both corrupt AND drifted. Appending beside the shortcode would build a profile the ingest
        // boundary refuses with a 400 — so "Add the live picture" would fail on the very group it exists for.
        String shortcode = "DbyhP29uyF5nHF-_saO60D-eic5SEtq5Qw3IAs0";
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "START Sonntag", shortcode))), "start", "START Sonntag", NEW);

        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(NEW);
    }

    @Test
    void cleaningHappensEvenWhenTheHashIsAlreadyKnown() {
        // Otherwise the early "already adopted" return would leave the malformed value in place forever.
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "START", "not-a-hash", NEW))), "start", "START", NEW);

        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(NEW);
    }

    @Test
    void REGRESSION_theTEXT_aloneIdentifiesAReferenceWhenTheRoleIsMissing() {
        // Live on 16/08: a field-name mismatch upstream delivered a NULL role, adoption matched nothing, and a
        // repair appeared to succeed while the live banner it was meant to add was silently dropped. The text
        // identifies a reference precisely on its own, so it must be enough.
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "GB AGENCY START Sonntag", OLD),
                        ref("end", "GB AGENCY ENDE Sonntag", OLD))),
                null, "GB AGENCY START Sonntag", NEW);

        List<VettedProfile.TypedMarkerReference> refs = out.weeklySchedule().days().get(0).references();
        assertThat(refs.get(0).dHashes()).containsExactly(NEW);
        assertThat(refs.get(1).dHashes()).containsExactly(OLD); // the END banner is untouched
    }

    @Test
    void withNEITHER_roleNorText_nothingIsTouched() {
        // Without either, adding the picture anywhere would be a guess — and a wrong hash is worse than none.
        VettedProfile in = profile(null, List.of(ref("start", "START", OLD)));
        assertThat(VettedProfileHashAdopter.append(in, null, null, NEW)).isSameAs(in);
        assertThat(VettedProfileHashAdopter.append(in, "  ", "  ", NEW)).isSameAs(in);
    }

    @Test
    void aReferenceWithNullHashListGainsTheNewOne() {
        VettedProfile.TypedMarkerReference nullHashes =
                new VettedProfile.TypedMarkerReference("start", null, "START", 4, "detected", "SC", null, null);
        VettedProfile out = VettedProfileHashAdopter.append(profile(null, List.of(nullHashes)), "start", NEW);
        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(NEW);
    }
}
