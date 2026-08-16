package com.reelypops.rpsupportgroup.group;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repairing a reference whose stored dHash is not a hash, from the reference's OWN stored picture.
 *
 * <p>The real fault (`glowbloggeragency`, 15/08/2026): a 39-character Instagram post shortcode sat where the Sunday
 * START hash belongs. It could never match, never contradict, and never took part in the threshold calibration — a
 * silent hole. The picture behind it was intact all along, so the hash is recomputable without any re-vet and
 * without touching Instagram (directive B1).</p>
 */
class VettedProfileHashRepairerTest {

    private static final String SHORTCODE = "DbyhP29uyF5nHF-_saO60D-eic5SEtq5Qw3IAs0"; // the real corrupt value
    private static final String GOOD = "1010".repeat(16);
    private static final String RECOMPUTED = "1100".repeat(16);
    private static final String LOCATOR = "27d823d2";

    private final List<String> unrepairable = new ArrayList<>();

    /** Stands in for "hash the picture stored against this locator". */
    private static Optional<String> storedImageHash(String locator) {
        return LOCATOR.equals(locator) ? Optional.of(RECOMPUTED) : Optional.empty();
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
    void REGRESSION_aPostShortcodeIsREPLACEDByTheHashOfTheReferencesOwnPicture() {
        VettedProfile out = VettedProfileHashRepairer.repair(
                profile(List.of(ref("start", "START Sonntag", LOCATOR, SHORTCODE)),
                        List.of(ref("start", "START Sonntag", LOCATOR, SHORTCODE))),
                VettedProfileHashRepairerTest::storedImageHash, unrepairable);

        // Replaced, NOT appended: a value that is not a hash is not evidence of anything.
        assertThat(out.detector().references().get(0).dHashes()).containsExactly(RECOMPUTED);
        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(RECOMPUTED);
        assertThat(unrepairable).isEmpty();
    }

    @Test
    void wellFormedHashesAlreadyPresentAreKept_inOrder() {
        VettedProfile out = VettedProfileHashRepairer.repair(
                profile(null, List.of(ref("start", "START", LOCATOR, GOOD, SHORTCODE))),
                VettedProfileHashRepairerTest::storedImageHash, unrepairable);

        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes())
                .containsExactly(GOOD, RECOMPUTED);
    }

    @Test
    void aReferenceThatIsAlreadyWellFormedIsUntouched_andTheProfileIsRETURNED_AS_IS() {
        VettedProfile in = profile(null, List.of(ref("start", "START", LOCATOR, GOOD)));

        // Same instance, so the caller can tell "nothing to repair" from "repaired" by identity and refuse to
        // write a pointless new profile version.
        assertThat(VettedProfileHashRepairer.repair(in, VettedProfileHashRepairerTest::storedImageHash, unrepairable))
                .isSameAs(in);
        assertThat(unrepairable).isEmpty();
    }

    @Test
    void aReferenceWhosePictureIsGoneIsLEFT_ALONE_andReported() {
        VettedProfile in = profile(null, List.of(ref("end", "ENDE Sonntag", "missing-locator", SHORTCODE)));

        VettedProfile out = VettedProfileHashRepairer.repair(in, VettedProfileHashRepairerTest::storedImageHash,
                unrepairable);

        // Emptying it would LOOK repaired while still matching nothing — and the administrator would never learn
        // that the picture is gone.
        assertThat(out).isSameAs(in);
        assertThat(unrepairable).singleElement().asString()
                .contains("end \"ENDE Sonntag\"")
                .contains("needs a re-vet");
    }

    @Test
    void aReferenceWithNoLocatorAtAllIsReportedTheSameWay() {
        VettedProfile in = profile(null, List.of(ref("start", null, null, SHORTCODE)));

        assertThat(VettedProfileHashRepairer.repair(in, VettedProfileHashRepairerTest::storedImageHash, unrepairable))
                .isSameAs(in);
        assertThat(unrepairable).singleElement().asString().contains("start"); // named by role when it has no text
    }

    @Test
    void oneRepairableAndOneNotYieldsBOTH_aRepairAndAReport() {
        VettedProfile out = VettedProfileHashRepairer.repair(
                profile(null, List.of(ref("start", "START", LOCATOR, SHORTCODE),
                        ref("end", "ENDE", "gone", SHORTCODE))),
                VettedProfileHashRepairerTest::storedImageHash, unrepairable);

        List<VettedProfile.TypedMarkerReference> refs = out.weeklySchedule().days().get(0).references();
        assertThat(refs.get(0).dHashes()).containsExactly(RECOMPUTED);
        assertThat(refs.get(1).dHashes()).containsExactly(SHORTCODE); // untouched
        assertThat(unrepairable).hasSize(1);
    }

    @Test
    void repairingTwiceDoesNotDuplicateTheRecomputedHash() {
        VettedProfile once = VettedProfileHashRepairer.repair(
                profile(null, List.of(ref("start", "START", LOCATOR, RECOMPUTED, SHORTCODE))),
                VettedProfileHashRepairerTest::storedImageHash, unrepairable);

        assertThat(once.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(RECOMPUTED);
    }

    @Test
    void aNullHashEntryCountsAsMalformed() {
        VettedProfile out = VettedProfileHashRepairer.repair(
                profile(null, List.of(ref("start", "START", LOCATOR, (String) null))),
                VettedProfileHashRepairerTest::storedImageHash, unrepairable);

        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(RECOMPUTED);
    }

    @Test
    void toleratesNullProfile_nullBlocks_nullDays_nullRefsAndNullEntries() {
        assertThat(VettedProfileHashRepairer.repair(null, VettedProfileHashRepairerTest::storedImageHash,
                unrepairable)).isNull();

        VettedProfile bare = new VettedProfile(definition(), null, "desc", null);
        assertThat(VettedProfileHashRepairer.repair(bare, VettedProfileHashRepairerTest::storedImageHash,
                unrepairable)).isSameAs(bare);

        VettedProfile noDays = new VettedProfile(definition(), null, "desc", new WeeklyScheduleDefinition(null));
        assertThat(VettedProfileHashRepairer.repair(noDays, VettedProfileHashRepairerTest::storedImageHash,
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
                VettedProfileHashRepairerTest::storedImageHash, unrepairable);
        assertThat(out.detector().references()).isNull();
        assertThat(out.weeklySchedule().days().get(2).references().get(0)).isNull();
        assertThat(out.weeklySchedule().days().get(2).references().get(1).dHashes()).containsExactly(RECOMPUTED);
    }

    @Test
    void aReferenceWithANullHashLISTIsNotAFault() {
        VettedProfile.TypedMarkerReference nullHashes =
                new VettedProfile.TypedMarkerReference("start", null, "START", 4, "detected", "SC", null, LOCATOR);
        VettedProfile in = profile(null, List.of(nullHashes));

        assertThat(VettedProfileHashRepairer.repair(in, VettedProfileHashRepairerTest::storedImageHash, unrepairable))
                .isSameAs(in);
    }
}
