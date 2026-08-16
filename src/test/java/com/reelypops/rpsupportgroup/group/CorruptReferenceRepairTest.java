package com.reelypops.rpsupportgroup.group;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The CORRUPT-reference half of the re-vet loop: the client discovers it, the cloud raises it as a re-vet reason,
 * and one click repairs it from the reference's own stored picture.
 *
 * <p>Separate from {@link DriftImageAdoptionTest} because the fault and the remedy are different in kind. Drift is a
 * stale-but-real picture, remedied by ADDING the newer one. A corrupt reference holds a value that is not a hash at
 * all — it matches nothing, contradicts nothing, and silently distorts the client's threshold calibration — and the
 * remedy needs nothing new: the picture is already stored, so the hash is recomputable in place.</p>
 */
class CorruptReferenceRepairTest {

    private static final String SHORTCODE = "DbyhP29uyF5nHF-_saO60D-eic5SEtq5Qw3IAs0";
    private static final String GOOD = "1010".repeat(16);
    private static final String LOCATOR = "27d823d2";

    private final SupportGroupConfigRepository configs = mock(SupportGroupConfigRepository.class);
    private final VettedProfileVersionRepository versions = mock(VettedProfileVersionRepository.class);
    private final DriftObservationRepository drifts = mock(DriftObservationRepository.class);
    private final MarkerImageEnricher enricher = mock(MarkerImageEnricher.class);
    private final MarkerImageStore imageStore = mock(MarkerImageStore.class);
    private final SupportGroupConfigService service =
            new SupportGroupConfigService(configs, versions, drifts, enricher, imageStore);

    /** A real decodable PNG — ImageDHash rejects anything it cannot decode. */
    private static byte[] png() {
        try {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    img.setRGB(x, y, (x * 16) << 16 | (y * 16) << 8);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A DIFFERENT decodable PNG — so its dHash genuinely differs from {@link #png()}. */
    private static byte[] differentPng() {
        try {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    img.setRGB(x, y, ((15 - x) * 16) << 8 | (y * 16));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static VettedProfile.TypedMarkerReference ref(String type, String text, String locator, String... hashes) {
        return new VettedProfile.TypedMarkerReference(type, List.of(hashes), text, 4, "detected", SHORTCODE,
                null, locator);
    }

    private SupportGroupConfig groupWith(VettedProfile.TypedMarkerReference... refs) {
        SupportGroupConfig c = SupportGroupConfig.createRequested("glowbloggeragency");
        GroupDefinition def = new GroupDefinition(SgType.TWO_MARKER, "Europe/Paris", List.of("glowbloggeragency"),
                null, null, "20:31", "20:31", 0, null, null, null, null, null, null);
        DayDefinition sunday = new DayDefinition(0, true, SgType.TWO_MARKER, "20:31", "20:31", 0, -1, null,
                "23:59", 0, "10:30", 1, 1, MarkerStyle.TEXT_OVERLAY, List.of(refs));
        // Saved directly on the entity: applyVettedProfile would (rightly) refuse to ingest a corrupt profile, and
        // this is exactly the pre-guard record we have to be able to repair.
        c.saveVettedProfile(new VettedProfile(def, null, "desc", new WeeklyScheduleDefinition(List.of(sunday))), 1L);
        when(configs.findByIgAccount("glowbloggeragency")).thenReturn(Optional.of(c));
        when(configs.save(any())).thenAnswer(i -> i.getArgument(0));
        when(versions.findByConfigIdOrderBySnapshotVersionDesc(any())).thenReturn(List.of());
        when(enricher.enrich(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        return c;
    }

    private DriftObservation openCorruptDrift(SupportGroupConfig c) {
        DriftObservation o = DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "device-1", null,
                null, null, null, 1, java.time.Instant.now());
        when(drifts.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(
                c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT)).thenReturn(List.of(o));
        when(drifts.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        return o;
    }

    @Test
    void REPAIRS_thePostShortcodeFromTheReferencesOwnStoredPicture_andClosesTheDrift() {
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        DriftObservation open = openCorruptDrift(c);
        MarkerImage stored = mock(MarkerImage.class);
        when(stored.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(stored));

        SupportGroupConfig out = service.repairMalformedReferenceHashes("glowbloggeragency");

        List<String> hashes = out.getVettedProfile().weeklySchedule().days().get(0).references().get(0).dHashes();
        assertThat(hashes).singleElement().asString().matches("[01]{64}"); // the shortcode is GONE
        assertThat(open.isResolved()).isTrue();
    }

    @Test
    void REPAIR_alsoAdoptsTheLIVE_bannerWhenItDIFFERS_fromTheVettedPicture() {
        // One decisive click: repairing the field and adopting the live banner are the same operator intent —
        // "make this reference right" — so they happen together rather than as two buttons whose ordering has to
        // be reasoned about. Both pictures end up carried, which is what widens what can be recognised.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        DriftObservation open = openCorruptDrift(c);
        open.measure("start", "GB AGENCY START Sonntag", null, null, "DcEj0SRu", "live-loc", "shortcode");
        MarkerImage vetted = mock(MarkerImage.class);
        when(vetted.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(vetted));
        MarkerImage live = mock(MarkerImage.class);
        when(live.getImage()).thenReturn(differentPng());
        when(imageStore.find("live-loc")).thenReturn(Optional.of(live));

        SupportGroupConfig out = service.repairMalformedReferenceHashes("glowbloggeragency");

        List<String> hashes = out.getVettedProfile().weeklySchedule().days().get(0).references().get(0).dHashes();
        assertThat(hashes).hasSize(2);                       // repaired + the live banner
        assertThat(hashes).allMatch(h -> h.matches("[01]{64}"));
        assertThat(hashes).doesNotContain(SHORTCODE);        // the junk is gone
        assertThat(open.isResolved()).isTrue();
    }

    @Test
    void repairDoesNotDUPLICATE_whenTheLiveBannerIsTheSamePicture() {
        // Identical pictures need no second entry — the decision the operator would otherwise have to make.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        DriftObservation open = openCorruptDrift(c);
        open.measure("start", "GB AGENCY START Sonntag", null, null, "DcEj0SRu", "live-loc", "shortcode");
        MarkerImage same = mock(MarkerImage.class);
        when(same.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(same));
        when(imageStore.find("live-loc")).thenReturn(Optional.of(same));

        SupportGroupConfig out = service.repairMalformedReferenceHashes("glowbloggeragency");

        assertThat(out.getVettedProfile().weeklySchedule().days().get(0).references().get(0).dHashes()).hasSize(1);
    }

    @Test
    void theDISTANCE_betweenTheTwoPicturesIsReported_soTheDecisionIsReadable() {
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        java.time.Instant now = java.time.Instant.now();
        DriftObservation corrupt = DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "d", null,
                null, null, null, 1, now);
        corrupt.measure("start", "GB AGENCY START Sonntag", null, null, "DcEj0SRu", "live-loc", "shortcode");
        when(drifts.findByConfigIdAndKindInAndResolvedFalseOrderByLastSeenAtDesc(any(), any()))
                .thenReturn(List.of(corrupt));
        MarkerImage vetted = mock(MarkerImage.class);
        when(vetted.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(vetted));
        MarkerImage live = mock(MarkerImage.class);
        when(live.getImage()).thenReturn(differentPng());
        when(imageStore.find("live-loc")).thenReturn(Optional.of(live));

        var v = service.markerReferenceDrifts("glowbloggeragency").get(0);

        assertThat(v.liveDistance()).isNotNull().isGreaterThan(0); // "adding this widens what can be recognised"
        assertThat(DriftObservationResponse.of(v).liveDistance()).isEqualTo(v.liveDistance());
    }

    @Test
    void theDistanceIsNullWhenThereIsNoLiveBannerToCompare() {
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        java.time.Instant now = java.time.Instant.now();
        DriftObservation corrupt = DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "d", null,
                null, null, null, 1, now);
        corrupt.measure("start", "GB AGENCY START Sonntag", null, null, null, null, "shortcode");
        when(drifts.findByConfigIdAndKindInAndResolvedFalseOrderByLastSeenAtDesc(any(), any()))
                .thenReturn(List.of(corrupt));
        MarkerImage vetted = mock(MarkerImage.class);
        when(vetted.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(vetted));

        assertThat(service.markerReferenceDrifts("glowbloggeragency").get(0).liveDistance()).isNull();
    }

    @Test
    void repairStillSucceedsWhenTheLiveBannerHasBeenRECLAIMED() {
        // The observation names a picture that is no longer in the store (retention, a cleared cache). The repair
        // must still fix the hash from the vetted picture rather than failing because the bonus is unavailable —
        // the live banner improves the outcome, it is not a precondition for it.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        DriftObservation open = openCorruptDrift(c);
        open.measure("start", "GB AGENCY START Sonntag", null, null, "DcEj0SRu", "gone-loc", "shortcode");
        MarkerImage vetted = mock(MarkerImage.class);
        when(vetted.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(vetted));
        when(imageStore.find("gone-loc")).thenReturn(Optional.empty());

        SupportGroupConfig out = service.repairMalformedReferenceHashes("glowbloggeragency");

        List<String> hashes = out.getVettedProfile().weeklySchedule().days().get(0).references().get(0).dHashes();
        assertThat(hashes).singleElement().asString().matches("[01]{64}");
        assertThat(open.isResolved()).isTrue();
    }

    @Test
    void refusesWhenEveryReferenceIsAlreadyWellFormed() {
        groupWith(ref("start", "START", LOCATOR, GOOD));

        assertThatThrownBy(() -> service.repairMalformedReferenceHashes("glowbloggeragency"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nothing to repair");
    }

    @Test
    void refusesAndNAMES_theReferenceWhenItsPictureIsNoLongerStored() {
        // "Nothing happened" must never be silent — the administrator has to learn that this one needs a re-vet.
        groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        when(imageStore.find(LOCATOR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.repairMalformedReferenceHashes("glowbloggeragency"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nothing could be repaired")
                .hasMessageContaining("GB AGENCY START Sonntag")
                .hasMessageContaining("needs a re-vet");
    }

    @Test
    void refusesWhenTheReferenceHasNoStoredPictureToRecomputeFrom() {
        // A reference vetted from an upload that was never retained, or hand-authored: there is no locator at all,
        // so nothing can be recomputed and the image store must not even be consulted.
        groupWith(ref("start", "START Sonntag", null, SHORTCODE));

        assertThatThrownBy(() -> service.repairMalformedReferenceHashes("glowbloggeragency"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nothing could be repaired");
    }

    @Test
    void refusesWhenTheGroupHasNoVettedProfileAtAll() {
        SupportGroupConfig c = SupportGroupConfig.createRequested("glowbloggeragency");
        when(configs.findByIgAccount("glowbloggeragency")).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.repairMalformedReferenceHashes("glowbloggeragency"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no vetted profile to repair");
    }

    @Test
    void aCorruptReferenceRAISES_needsRevet_andSortsAHEAD_ofEveryOtherReason() {
        // ⚠️ The defect this guards: a kind missing from the re-vet derivation is stored, listed, and INVISIBLE on
        // the needs-re-vet surface. MARKER_IMAGE_DRIFT shipped that way in #66.
        SupportGroupConfig c = groupWith(ref("start", "START", LOCATOR, GOOD));
        java.time.Instant now = java.time.Instant.now();
        when(drifts.findByConfigIdAndResolvedFalse(c.getId())).thenReturn(List.of(
                DriftObservation.first(c.getId(), DriftKind.NEW_OWNER, "d", null, "someone", null, null, 1, now),
                DriftObservation.first(c.getId(), DriftKind.MARKER_DISAGREE, "d", null, null, 3, 12, 1, now),
                DriftObservation.first(c.getId(), DriftKind.MARKER_IMAGE_DRIFT, "d", null, null, null, null, 1, now),
                DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "d", null, null, null, null,
                        1, now)));

        SupportGroupConfigService.RevetStatus status = service.revetStatus(c);

        assertThat(status.needsRevet()).isTrue();
        assertThat(status.reasons()).extracting(RevetReason::kind).containsExactly(
                DriftKind.MARKER_REFERENCE_CORRUPT, // a permanently unmatchable reference, and silent
                DriftKind.MARKER_IMAGE_DRIFT,       // real, measured, one click to fix
                DriftKind.MARKER_DISAGREE,
                DriftKind.NEW_OWNER);
    }

    @Test
    void theDriftLISTING_carriesBothReferenceKinds_butNotNominations() {
        SupportGroupConfig c = groupWith(ref("start", "START", LOCATOR, GOOD));
        java.time.Instant now = java.time.Instant.now();
        DriftObservation corrupt = DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "d", null,
                null, null, null, 1, now);
        corrupt.measure("start", "GB AGENCY START Sonntag", null, null, null, null,
                "looks like an Instagram post shortcode");
        when(drifts.findByConfigIdAndKindInAndResolvedFalseOrderByLastSeenAtDesc(any(), any()))
                .thenReturn(List.of(corrupt));

        List<SupportGroupConfigService.ReferenceDriftView> out =
                service.markerReferenceDrifts("glowbloggeragency");

        assertThat(out).singleElement().satisfies(v -> {
            assertThat(v.observation().getMarkerText()).isEqualTo("GB AGENCY START Sonntag");
            assertThat(v.observation().getDetail()).contains("shortcode");
        });
        assertThat(DriftObservationResponse.of(corrupt).detail()).contains("shortcode");
        assertThat(DriftObservationResponse.of(corrupt).markerText()).isEqualTo("GB AGENCY START Sonntag");
    }

    @Test
    void aCORRUPT_driftCarriesTheREFERENCES_OWN_picture_soTheAdminSeesWhatWillBeWritten() {
        // Repair recomputes from bytes already held, so the preview must be the reference's OWN image. Showing a
        // client capture here would promise something the remedy will not do.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        java.time.Instant now = java.time.Instant.now();
        DriftObservation corrupt = DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "d", null,
                null, null, null, 1, now);
        corrupt.measure("start", "GB AGENCY START Sonntag", null, null, null, null, "looks like a shortcode");
        when(drifts.findByConfigIdAndKindInAndResolvedFalseOrderByLastSeenAtDesc(any(), any()))
                .thenReturn(List.of(corrupt));

        assertThat(service.markerReferenceDrifts("glowbloggeragency"))
                .singleElement()
                .extracting(SupportGroupConfigService.ReferenceDriftView::referenceImageLocator)
                .isEqualTo(LOCATOR);
    }

    @Test
    void theTEXT_picksTheRightReferencesPictureWhenARoleHasSeveral() {
        // glow carries two START banners. Previewing the wrong one would show a picture the repair never touches.
        SupportGroupConfig c = groupWith(
                ref("start", "G B AGENCY START", "weekday-loc", GOOD),
                ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        java.time.Instant now = java.time.Instant.now();
        DriftObservation corrupt = DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "d", null,
                null, null, null, 1, now);
        corrupt.measure("start", "GB AGENCY START Sonntag", null, null, null, null, "looks like a shortcode");
        when(drifts.findByConfigIdAndKindInAndResolvedFalseOrderByLastSeenAtDesc(any(), any()))
                .thenReturn(List.of(corrupt));

        assertThat(service.markerReferenceDrifts("glowbloggeragency"))
                .singleElement()
                .extracting(SupportGroupConfigService.ReferenceDriftView::referenceImageLocator)
                .isEqualTo(LOCATOR);
    }

    @Test
    void noPictureToPreviewWhenTheReferenceHasNoStoredImage() {
        // Informative on its own: the repair has nothing to recompute from, so the surface must not offer a
        // button that will only 409.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", null, SHORTCODE));
        java.time.Instant now = java.time.Instant.now();
        DriftObservation corrupt = DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "d", null,
                null, null, null, 1, now);
        corrupt.measure("start", "GB AGENCY START Sonntag", null, null, null, null, "looks like a shortcode");
        when(drifts.findByConfigIdAndKindInAndResolvedFalseOrderByLastSeenAtDesc(any(), any()))
                .thenReturn(List.of(corrupt));

        assertThat(service.markerReferenceDrifts("glowbloggeragency"))
                .singleElement()
                .extracting(SupportGroupConfigService.ReferenceDriftView::referenceImageLocator)
                .isNull();
    }

    @Test
    void BOTH_picturesAndBOTH_hashesAreSurfacedForComparison() {
        // What makes the fault self-evident rather than described: the vetted picture and the live one side by
        // side, each with the hash it really produces, and the value the reference stores today — 39 characters of
        // base64url next to two rows of 64 binary digits.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        java.time.Instant now = java.time.Instant.now();
        DriftObservation corrupt = DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "d", null,
                null, null, null, 1, now);
        corrupt.measure("start", "GB AGENCY START Sonntag", null, null, "DcEj0SRu", "live-loc", "looks like a shortcode");
        when(drifts.findByConfigIdAndKindInAndResolvedFalseOrderByLastSeenAtDesc(any(), any()))
                .thenReturn(List.of(corrupt));
        MarkerImage storedRef = mock(MarkerImage.class);
        when(storedRef.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(storedRef));
        MarkerImage live = mock(MarkerImage.class);
        when(live.getImage()).thenReturn(png());
        when(imageStore.find("live-loc")).thenReturn(Optional.of(live));

        var v = service.markerReferenceDrifts("glowbloggeragency").get(0);

        assertThat(v.referenceImageLocator()).isEqualTo(LOCATOR);     // the vetted picture
        assertThat(v.observation().getEvidenceImageLocator()).isEqualTo("live-loc"); // the live banner
        assertThat(v.referenceImageHash()).matches("[01]{64}");       // what a repair would write
        assertThat(v.evidenceImageHash()).matches("[01]{64}");        // what adopting would write
        assertThat(v.storedValue()).isEqualTo(SHORTCODE);             // what is stored today — the fault itself
        assertThat(DriftObservationResponse.of(v).storedValue()).isEqualTo(SHORTCODE);
        assertThat(DriftObservationResponse.of(v).evidenceImageHash()).matches("[01]{64}");
    }

    @Test
    void recordingACorruptReferenceStoresTheFaultAndTheReferenceItBelongsTo() {
        SupportGroupConfig c = groupWith(ref("start", "START", LOCATOR, GOOD));
        when(drifts.findByConfigIdAndKindAndReporterDeviceIdAndNominatedOwnerHandleIsNull(any(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(drifts.save(any())).thenAnswer(i -> i.getArgument(0));

        DriftObservation saved = service.recordDrift("glowbloggeragency", DriftKind.MARKER_REFERENCE_CORRUPT,
                "device-1", UUID.randomUUID(), null, null, null, 1, "start", "GB AGENCY START Sonntag",
                "value=" + SHORTCODE + " looks like an Instagram post shortcode", null, null, null, new byte[0]);

        assertThat(saved.getKind()).isEqualTo(DriftKind.MARKER_REFERENCE_CORRUPT);
        assertThat(saved.getMarkerText()).isEqualTo("GB AGENCY START Sonntag");
        assertThat(saved.getDetail()).contains(SHORTCODE);
        assertThat(saved.getEvidenceImageLocator()).isNull(); // no picture is needed to describe a data fault
        assertThat(c).isNotNull();
    }
}
