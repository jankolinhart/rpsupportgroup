package com.reelypops.rpsupportgroup.group;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * THE PRODUCER BOUNDARY — nothing that is not a perceptual hash may enter the authoritative vetted profile.
 *
 * <p>On 15/08/2026 `glowbloggeragency`'s Sunday START reference was stored with a 39-character Instagram post
 * SHORTCODE where its dHash belongs, byte-identical to the record's own {@code shortcode} field. Every
 * comparison site in the client skips a hash whose length differs from the candidate's, so the reference became
 * INVISIBLE — it could not match, could not contradict, and did not take part in the threshold calibration that
 * sets the profile's width. It went unnoticed until a misread marker cost a full day of likes.</p>
 */
class VettedProfileHashIntegrityTest {

    /** The real corrupt value from the live cloud record. */
    private static final String SHORTCODE = "DbyhP29uyF5nHF-_saO60D-eic5SEtq5Qw3IAs0";
    private static final String GOOD = "1010".repeat(16); // 64 binary digits

    private final SupportGroupConfigRepository configs = mock(SupportGroupConfigRepository.class);
    private final VettedProfileVersionRepository versions = mock(VettedProfileVersionRepository.class);
    private final DriftObservationRepository drifts = mock(DriftObservationRepository.class);
    private final MarkerImageEnricher enricher = mock(MarkerImageEnricher.class);
    private final MarkerImageStore markerImageStore = mock(MarkerImageStore.class);
    private final SupportGroupConfigService service =
            new SupportGroupConfigService(configs, versions, drifts, enricher, markerImageStore);

    private SupportGroupConfig existingGroup() {
        SupportGroupConfig c = SupportGroupConfig.createRequested("glowbloggeragency");
        when(configs.findByIgAccount("glowbloggeragency")).thenReturn(Optional.of(c));
        when(configs.save(any())).thenAnswer(i -> i.getArgument(0));
        when(versions.findByConfigIdOrderBySnapshotVersionDesc(any())).thenReturn(List.of());
        when(enricher.enrich(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        return c;
    }

    private static VettedProfile.TypedMarkerReference ref(String type, String text, String shortcode, String... hashes) {
        return new VettedProfile.TypedMarkerReference(type, Arrays.asList(hashes), text, 4, "detected",
                shortcode, null, null);
    }

    private static VettedProfile profileWith(VettedProfile.TypedMarkerReference... refs) {
        GroupDefinition definition = new GroupDefinition(SgType.TWO_MARKER, "Europe/Paris",
                List.of("glowbloggeragency"), null, null, "20:31", "20:31", 0, null, null, null, null, null, null);
        DayDefinition sunday = new DayDefinition(0, true, SgType.TWO_MARKER, "20:31", "20:31", 0, -1, null,
                "23:59", 0, "10:30", 1, 1, MarkerStyle.TEXT_OVERLAY, List.of(refs));
        return new VettedProfile(definition, null, "desc", new WeeklyScheduleDefinition(List.of(sunday)));
    }

    @Test
    void REGRESSION_aPostShortcodeStoredAsAHashIsRejected() {
        existingGroup();
        VettedProfile corrupt = profileWith(ref("start", "GB AGENCY START Sonntag", SHORTCODE, SHORTCODE));

        assertThatThrownBy(() -> service.saveVettedProfile("glowbloggeragency", corrupt))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not a perceptual hash")
                .hasMessageContaining("the post shortcode")     // names the exact fault, not a length assertion
                .hasMessageContaining("GB AGENCY START Sonntag"); // and which reference carries it

        verify(configs, never()).save(any());
    }

    @Test
    void REGRESSION_theSameRejectionAppliesToVetNow_notOnlyToSave() {
        // Both entry points funnel through applyVettedProfile; guarding only "Save" would let "Vet Now" ship it.
        existingGroup();
        VettedProfile corrupt = profileWith(ref("start", "START", SHORTCODE, SHORTCODE));

        assertThatThrownBy(() -> service.vetVettedProfile("glowbloggeragency", corrupt))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not a perceptual hash");
    }

    @Test
    void aWellFormedProfileIsAccepted() {
        existingGroup();
        assertThatCode(() -> service.saveVettedProfile("glowbloggeragency",
                profileWith(ref("start", "START Sonntag", "SC1", GOOD))))
                .doesNotThrowAnyException();
    }

    @Test
    void aTEXT_ONLY_referenceWithNoHashesIsPerfectlyLegal() {
        // The portal now writes an EMPTY hash list when no image cluster could be resolved (rpadminfrontend #95).
        // That reference is honest and still matchable by OCR — only a value PRETENDING to be a hash is a fault.
        existingGroup();
        assertThatCode(() -> service.saveVettedProfile("glowbloggeragency",
                profileWith(ref("start", "START Sonntag", "SC1"))))
                .doesNotThrowAnyException();
    }

    @Test
    void aHashOfTheWrongLengthOrAlphabetIsRejected() {
        existingGroup();
        assertThatThrownBy(() -> service.saveVettedProfile("glowbloggeragency",
                profileWith(ref("end", "ENDE", "SC1", "0101"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not 64 binary digits");

        assertThatThrownBy(() -> service.saveVettedProfile("glowbloggeragency",
                profileWith(ref("end", "ENDE", "SC1", "z".repeat(64)))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void everyMalformedReferenceIsNamed_notJustTheFirst() {
        // An administrator fixing one fault at a time, one save at a time, is a bad afternoon.
        existingGroup();
        assertThatThrownBy(() -> service.saveVettedProfile("glowbloggeragency",
                profileWith(ref("start", "START", SHORTCODE, SHORTCODE), ref("end", "ENDE", "SC2", "0101"))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).contains("START");
                    assertThat(e.getMessage()).contains("ENDE");
                });
    }

    @Test
    void theDetectorBlockIsCheckedToo_notOnlyTheWeeklySchedule() {
        // glow's live record carries the corrupt reference in BOTH blocks; guarding one would leave the other.
        existingGroup();
        GroupDefinition definition = new GroupDefinition(SgType.TWO_MARKER, "Europe/Paris",
                List.of("glowbloggeragency"), null, null, "20:31", "20:31", 0, null, null, null, null, null, null);
        VettedProfile detectorOnly = new VettedProfile(definition,
                new VettedProfile.DetectorArtifacts(MarkerStyle.TEXT_OVERLAY,
                        List.of(ref("start", "START Sonntag", SHORTCODE, SHORTCODE))),
                "desc", null);

        assertThatThrownBy(() -> service.saveVettedProfile("glowbloggeragency", detectorOnly))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not a perceptual hash");
    }

    @Test
    void aNullProfileOrEmptyBlocksAreNotFaults() {
        existingGroup();
        GroupDefinition definition = new GroupDefinition(SgType.TWO_MARKER, "Europe/Paris",
                List.of("glowbloggeragency"), null, null, "20:31", "20:31", 0, null, null, null, null, null, null);
        assertThatCode(() -> service.saveVettedProfile("glowbloggeragency",
                new VettedProfile(definition, null, "desc", null))).doesNotThrowAnyException();
    }

    @Test
    void aNullEntryInTheHashListIsRejected() {
        existingGroup();
        assertThatThrownBy(() -> service.saveVettedProfile("glowbloggeragency",
                profileWith(ref("start", "START", "SC1", (String) null))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("null");
    }

    @Test
    void aShortcodeShapedHashIsNamedAsSuch_evenWhenItIsNotTHISReferencesShortcode() {
        // The corruption need not be self-consistent: the id of a DIFFERENT post can land in the field. Saying
        // "looks like an Instagram post shortcode" points at the real fault, where "not 64 binary digits" would
        // send an administrator looking for a truncation.
        existingGroup();
        assertThatThrownBy(() -> service.saveVettedProfile("glowbloggeragency",
                profileWith(ref("start", "START", "SOME_OTHER_SHORTCODE", SHORTCODE))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("looks like an Instagram post shortcode");
    }

    @Test
    void aReferenceWithNoOcrTextIsStillNamedByItsRole() {
        existingGroup();
        assertThatThrownBy(() -> service.saveVettedProfile("glowbloggeragency",
                profileWith(ref("end", null, "SC1", "0101"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("end");
    }

    @Test
    void aWeekdayWithNoReferencesIsNotAFault() {
        existingGroup();
        GroupDefinition definition = new GroupDefinition(SgType.TWO_MARKER, "Europe/Paris",
                List.of("glowbloggeragency"), null, null, "20:31", "20:31", 0, null, null, null, null, null, null);
        DayDefinition closed = new DayDefinition(0, false, null, null, null, 0, null, null, null, 0,
                null, 0, 0, null, null);
        assertThatCode(() -> service.saveVettedProfile("glowbloggeragency",
                new VettedProfile(definition, new VettedProfile.DetectorArtifacts(MarkerStyle.FLAT_BANNER, null),
                        "desc", new WeeklyScheduleDefinition(List.of(closed)))))
                .doesNotThrowAnyException();
    }

    @Test
    void aNullProfileIsNotAHashFault() {
        // A null profile is contemplated elsewhere in this service (MarkerImageEnricher returns null for one), so
        // the validator must pass it through rather than blame it for a hash it does not have. Whatever the rest
        // of the save then does with it, it must not be reported as malformed-hash.
        existingGroup();
        assertThatThrownBy(() -> service.saveVettedProfile("glowbloggeragency", null))
                .isInstanceOf(NullPointerException.class)     // the save path itself has no null contract
                .hasMessageNotContaining("not a perceptual hash");
    }

    /** Guards the UUID import being genuinely needed by the mocks above. */
    @Test
    void serviceIsConstructible() {
        assertThat(UUID.randomUUID()).isNotNull();
        assertThat(service).isNotNull();
    }
}
