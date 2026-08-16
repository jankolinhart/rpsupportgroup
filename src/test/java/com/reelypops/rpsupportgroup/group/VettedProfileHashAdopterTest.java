package com.reelypops.rpsupportgroup.group;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adoption ADDS a banner's newer picture; it never replaces the older one.
 *
 * <p>Measured on `glowbloggeragency` (16/08/2026): carrying both pictures is what lets the client's threshold
 * calibration see that Sunday's START and the ENDE banner are only 9 bits apart, dropping its floor from 10 to 8.
 * Replacing would have kept the floor at 10 and left the role misread possible — so "append, don't replace" is
 * load-bearing, not politeness to the old reference.</p>
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
    void APPENDS_theNewPicture_keepingTheOldOne() {
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(List.of(ref("start", "START Sonntag", OLD)), List.of(ref("start", "START Sonntag", OLD))),
                "start", NEW);

        assertThat(out.detector().references().get(0).dHashes()).containsExactly(OLD, NEW);
        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(OLD, NEW);
    }

    @Test
    void leavesOtherRolesAlone() {
        VettedProfile out = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "START", OLD), ref("end", "ENDE", OLD))), "start", NEW);

        List<VettedProfile.TypedMarkerReference> refs = out.weeklySchedule().days().get(0).references();
        assertThat(refs.get(0).dHashes()).containsExactly(OLD, NEW);
        assertThat(refs.get(1).dHashes()).containsExactly(OLD); // the END banner did not change
    }

    @Test
    void adoptingTwiceDoesNotAccumulateDuplicates() {
        // An administrator clicking again, or two clients reporting the same drift, must be harmless.
        VettedProfile once = VettedProfileHashAdopter.append(
                profile(null, List.of(ref("start", "START", OLD))), "start", NEW);
        VettedProfile twice = VettedProfileHashAdopter.append(once, "start", NEW);

        assertThat(twice.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(OLD, NEW);
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
        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(OLD, NEW);
    }

    @Test
    void nothingIsTouchedWithoutARoleOrAHash() {
        // Without knowing WHICH reference the picture belongs to, adding it anywhere would be a guess — and a wrong
        // hash on a reference is worse than a missing one.
        VettedProfile in = profile(null, List.of(ref("start", "START", OLD)));

        assertThat(VettedProfileHashAdopter.append(in, null, NEW)).isSameAs(in);
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
        assertThat(refs.get(1).dHashes()).containsExactly(OLD, NEW);
    }

    @Test
    void aReferenceWithNullHashListGainsTheNewOne() {
        VettedProfile.TypedMarkerReference nullHashes =
                new VettedProfile.TypedMarkerReference("start", null, "START", 4, "detected", "SC", null, null);
        VettedProfile out = VettedProfileHashAdopter.append(profile(null, List.of(nullHashes)), "start", NEW);
        assertThat(out.weeklySchedule().days().get(0).references().get(0).dHashes()).containsExactly(NEW);
    }
}
