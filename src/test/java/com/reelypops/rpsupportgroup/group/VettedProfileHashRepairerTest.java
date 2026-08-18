package com.reelypops.rpsupportgroup.group;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repairing a reference whose stored dHash is not a hash, by copying a fingerprint a CLIENT computed for it.
 *
 * <p>The real fault (`glowbloggeragency`, 15/08/2026): a 39-character Instagram post shortcode sat where the Sunday
 * START hash belongs. It could never match, never contradict, and never took part in the threshold calibration — a
 * silent hole.</p>
 *
 * <p><strong>⚠️ The repair used to recompute the hash HERE, and that was a second silent fault (fixed 18/08/2026).</strong>
 * This service's Java hasher and the client's sharp/libvips one land 15–34 bits apart on identical bytes, while
 * clients accept a match at 4–10 — so the "repaired" reference was well-formed, plausible, and unmatchable. The
 * fingerprint now comes from a client (the deep-scrape corpus for the reference's own post, or a live picture a
 * client delivered). No Instagram access is involved either way (directive B1).</p>
 */
class VettedProfileHashRepairerTest {

    private static final String SHORTCODE = "DbyhP29uyF5nHF-_saO60D-eic5SEtq5Qw3IAs0"; // the real corrupt value
    private static final String GOOD = "1010".repeat(16);
    private static final String FROM_CLIENT = "1100".repeat(16);
    private static final String LOCATOR = "27d823d2";

    private final List<String> unrepairable = new ArrayList<>();

    /** Stands in for "a client has reported a fingerprint for this reference's picture". */
    private static Optional<String> clientHash(VettedProfile.TypedMarkerReference r) {
        return LOCATOR.equals(r.imageLocator()) ? Optional.of(FROM_CLIENT) : Optional.empty();
    }

    private static VettedProfile.TypedMarkerReference ref(String type, String text, String locator, String... hashes) {
        return new VettedProfile.TypedMarkerReference(type, Arrays.asList(hashes), text, 4, "detected", "SC",
                null, locator);
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
    void REGRESSION_aPostShortcodeIsREPLACEDByTheFingerprintAClientComputed() {
        VettedProfile out = VettedProfileHashRepairer.repair(
                profile(List.of(ref("start", "START Sonntag", LOCATOR, SHORTCODE)),
                        List.of(ref("start", "START Sonntag", LOCATOR, SHORTCODE))),
                VettedProfileHashRepairerTest::clientHash, unrepairable);

        // Replaced, NOT appended: a value that is not a hash is not evidence of anything.
        assertThat(out.detector().references().get(0).dHashes()).containsExactly(FROM_CLIENT);
        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(FROM_CLIENT);
        assertThat(unrepairable).isEmpty();
    }

    @Test
    void wellFormedHashesAlreadyPresentAreKept_inOrder() {
        VettedProfile out = VettedProfileHashRepairer.repair(
                profile(null, List.of(ref("start", "START", LOCATOR, GOOD, SHORTCODE))),
                VettedProfileHashRepairerTest::clientHash, unrepairable);

        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes())
                .containsExactly(GOOD, FROM_CLIENT);
    }

    @Test
    void aReferenceThatIsAlreadyWellFormedIsUntouched_andTheProfileIsRETURNED_AS_IS() {
        VettedProfile in = profile(null, List.of(ref("start", "START", LOCATOR, GOOD)));

        // Same instance, so the caller can tell "nothing to repair" from "repaired" by identity and refuse to
        // write a pointless new profile version.
        assertThat(VettedProfileHashRepairer.repair(in, VettedProfileHashRepairerTest::clientHash, unrepairable))
                .isSameAs(in);
        assertThat(unrepairable).isEmpty();
    }

    @Test
    void aReferenceNoClientHasEverFingerprintedIsLEFT_ALONE_andReported() {
        VettedProfile in = profile(null, List.of(ref("end", "ENDE Sonntag", "missing-locator", SHORTCODE)));

        VettedProfile out = VettedProfileHashRepairer.repair(in, VettedProfileHashRepairerTest::clientHash,
                unrepairable);

        // Emptying it would look repaired while matching nothing; minting a hash here would ALSO look repaired
        // while matching nothing. Only an honest "needs a re-vet" stays true.
        assertThat(out).isSameAs(in);
        assertThat(unrepairable).singleElement().asString()
                .contains("end \"ENDE Sonntag\"")
                .contains("needs a re-vet");
    }

    @Test
    void aReferenceWithNothingToLookItUpByIsReportedTheSameWay() {
        VettedProfile in = profile(null, List.of(ref("start", null, null, SHORTCODE)));

        assertThat(VettedProfileHashRepairer.repair(in, VettedProfileHashRepairerTest::clientHash, unrepairable))
                .isSameAs(in);
        assertThat(unrepairable).singleElement().asString().contains("start"); // named by role when it has no text
    }

    @Test
    void oneRepairableAndOneNotYieldsBOTH_aRepairAndAReport() {
        VettedProfile out = VettedProfileHashRepairer.repair(
                profile(null, List.of(ref("start", "START", LOCATOR, SHORTCODE),
                        ref("end", "ENDE", "gone", SHORTCODE))),
                VettedProfileHashRepairerTest::clientHash, unrepairable);

        List<VettedProfile.TypedMarkerReference> refs = out.weeklySchedule().days().get(0).references();
        assertThat(refs.get(0).dHashes()).containsExactly(FROM_CLIENT);
        assertThat(refs.get(1).dHashes()).containsExactly(SHORTCODE); // untouched
        assertThat(unrepairable).hasSize(1);
    }

    @Test
    void repairingTwiceDoesNotDuplicateTheRecomputedHash() {
        VettedProfile once = VettedProfileHashRepairer.repair(
                profile(null, List.of(ref("start", "START", LOCATOR, FROM_CLIENT, SHORTCODE))),
                VettedProfileHashRepairerTest::clientHash, unrepairable);

        assertThat(once.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(FROM_CLIENT);
    }

    @Test
    void aNullHashEntryCountsAsMalformed() {
        VettedProfile out = VettedProfileHashRepairer.repair(
                profile(null, List.of(ref("start", "START", LOCATOR, (String) null))),
                VettedProfileHashRepairerTest::clientHash, unrepairable);

        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(FROM_CLIENT);
    }

    @Test
    void toleratesNullProfile_nullBlocks_nullDays_nullRefsAndNullEntries() {
        assertThat(VettedProfileHashRepairer.repair(null, VettedProfileHashRepairerTest::clientHash,
                unrepairable)).isNull();

        VettedProfile bare = new VettedProfile(definition(), null, "desc", null);
        assertThat(VettedProfileHashRepairer.repair(bare, VettedProfileHashRepairerTest::clientHash,
                unrepairable)).isSameAs(bare);

        VettedProfile noDays = new VettedProfile(definition(), null, "desc", new WeeklyScheduleDefinition(null));
        assertThat(VettedProfileHashRepairer.repair(noDays, VettedProfileHashRepairerTest::clientHash,
                unrepairable)).isSameAs(noDays);

        DayDefinition dayWithoutRefs = new DayDefinition(1, false, null, null, null, 0, null, null, null, 0,
                null, 0, 0, null, null);
        DayDefinition dayWithNullEntry = new DayDefinition(0, true, SgType.TWO_MARKER, "20:31", "20:31", 0, -1,
                null, "23:59", 0, "10:30", 1, 1, MarkerStyle.TEXT_OVERLAY,
                Arrays.asList(null, ref("start", "START", LOCATOR, SHORTCODE)));
        VettedProfile mixed = new VettedProfile(definition(),
                new VettedProfile.DetectorArtifacts(MarkerStyle.TEXT_OVERLAY, null), "desc",
                new WeeklyScheduleDefinition(Arrays.asList(dayWithoutRefs, null, dayWithNullEntry)));

        VettedProfile out = VettedProfileHashRepairer.repair(mixed,
                VettedProfileHashRepairerTest::clientHash, unrepairable);
        assertThat(out.detector().references()).isNull();
        assertThat(out.weeklySchedule().days().get(2).references().get(0)).isNull();
        assertThat(out.weeklySchedule().days().get(2).references().get(1).dHashes()).containsExactly(FROM_CLIENT);
    }

    @Test
    void aReferenceWithANullHashLISTIsNotAFault() {
        VettedProfile.TypedMarkerReference nullHashes =
                new VettedProfile.TypedMarkerReference("start", null, "START", 4, "detected", "SC", null, LOCATOR);
        VettedProfile in = profile(null, List.of(nullHashes));

        assertThat(VettedProfileHashRepairer.repair(in, VettedProfileHashRepairerTest::clientHash, unrepairable))
                .isSameAs(in);
    }
}
