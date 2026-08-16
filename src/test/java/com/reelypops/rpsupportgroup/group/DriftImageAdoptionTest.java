package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.corpus.MarkerCorpusService;
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
 * The re-vet loop, end to end on the service: a client reports MEASURED drift with the picture the marker is
 * actually being posted with, and an administrator adopts that picture into the vetted profile in one click.
 *
 * <p>Directive B1 is what makes this shape necessary — no cloud service ever contacts Instagram, so the picture
 * arrives because the client captured and uploaded it, or an administrator never sees it at all.</p>
 */
class DriftImageAdoptionTest {

    private static final String LOCATOR = "abc123";

    private final SupportGroupConfigRepository configs = mock(SupportGroupConfigRepository.class);
    private final VettedProfileVersionRepository versions = mock(VettedProfileVersionRepository.class);
    private final DriftObservationRepository drifts = mock(DriftObservationRepository.class);
    private final MarkerImageEnricher enricher = mock(MarkerImageEnricher.class);
    private final MarkerImageStore imageStore = mock(MarkerImageStore.class);
    private final SupportGroupConfigService service =
            new SupportGroupConfigService(configs, versions, drifts, enricher, imageStore);

    /** A real decodable PNG — ImageDHash rejects anything it cannot decode, so a stub byte[] will not do. */
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

    private SupportGroupConfig vettedGroup() {
        SupportGroupConfig c = SupportGroupConfig.createRequested("glowbloggeragency");
        GroupDefinition def = new GroupDefinition(SgType.TWO_MARKER, "Europe/Paris", List.of("glowbloggeragency"),
                null, null, "20:31", "20:31", 0, null, null, null, null, null, null);
        VettedProfile.TypedMarkerReference start = new VettedProfile.TypedMarkerReference(
                "start", List.of("1010".repeat(16)), "GB AGENCY START Sonntag", 4, "detected", "SC", null, null);
        DayDefinition sunday = new DayDefinition(0, true, SgType.TWO_MARKER, "20:31", "20:31", 0, -1, null,
                "23:59", 0, "10:30", 1, 1, MarkerStyle.TEXT_OVERLAY, List.of(start));
        c.saveVettedProfile(new VettedProfile(def, null, "desc", new WeeklyScheduleDefinition(List.of(sunday))), 1L);
        when(configs.findByIgAccount("glowbloggeragency")).thenReturn(Optional.of(c));
        when(configs.save(any())).thenAnswer(i -> i.getArgument(0));
        when(versions.findByConfigIdOrderBySnapshotVersionDesc(any())).thenReturn(List.of());
        when(enricher.enrich(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        return c;
    }

    private DriftObservation measuredDrift(SupportGroupConfig c, String locator) {
        DriftObservation o = DriftObservation.first(c.getId(), DriftKind.MARKER_IMAGE_DRIFT, "device-1", null, null,
                null, null, 1, java.time.Instant.now());
        o.measure("start", 15, 10, "DcEj0SRu", locator);
        when(drifts.findById(any())).thenReturn(Optional.of(o));
        when(drifts.save(any())).thenAnswer(i -> i.getArgument(0));
        return o;
    }

    @Test
    void recordingDriftStoresThePictureAndTheMeasurement() {
        SupportGroupConfig c = vettedGroup();
        when(drifts.findByConfigIdAndKindAndReporterDeviceIdAndNominatedOwnerHandleIsNull(any(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(drifts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(imageStore.capture(any())).thenReturn(Optional.of(LOCATOR));

        DriftObservation saved = service.recordDrift("glowbloggeragency", DriftKind.MARKER_IMAGE_DRIFT, "device-1",
                null, null, null, null, 1, "start", 15, 10, "DcEj0SRu", png());

        assertThat(saved.getImageDistance()).isEqualTo(15);
        assertThat(saved.getImageThreshold()).isEqualTo(10);
        assertThat(saved.getMarkerRole()).isEqualTo("start");
        assertThat(saved.getEvidencePostId()).isEqualTo("DcEj0SRu");
        assertThat(saved.getEvidenceImageLocator()).isEqualTo(LOCATOR);
        assertThat(c).isNotNull();
    }

    @Test
    void aDriftWithNoPictureIsStillRecorded() {
        // The measurement is the signal; the picture only makes it actionable in one click.
        vettedGroup();
        when(drifts.findByConfigIdAndKindAndReporterDeviceIdAndNominatedOwnerHandleIsNull(any(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(drifts.save(any())).thenAnswer(i -> i.getArgument(0));

        DriftObservation saved = service.recordDrift("glowbloggeragency", DriftKind.MARKER_IMAGE_DRIFT, "device-1",
                null, null, null, null, 1, "start", 15, 10, "DcEj0SRu", new byte[0]);

        assertThat(saved.getImageDistance()).isEqualTo(15);
        assertThat(saved.getEvidenceImageLocator()).isNull();
    }

    @Test
    void ADOPTING_addsTheNewHashWithoutRemovingTheOld() {
        SupportGroupConfig c = vettedGroup();
        DriftObservation o = measuredDrift(c, LOCATOR);
        MarkerImage stored = mock(MarkerImage.class);
        when(stored.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(stored));

        SupportGroupConfig out = service.adoptDriftedMarkerImage("glowbloggeragency", UUID.randomUUID());

        VettedProfile.TypedMarkerReference ref =
                out.getVettedProfile().weeklySchedule().days().get(0).references().get(0);
        assertThat(ref.dHashes()).hasSize(2);
        assertThat(ref.dHashes().get(0)).isEqualTo("1010".repeat(16)); // the old picture is KEPT
        assertThat(ref.dHashes().get(1)).matches("[01]{64}");          // the newly adopted one
        // The DISPLAY follows the newest picture — and survives MarkerImageEnricher, which re-derives the locator
        // from the corpus on every save and would otherwise put the superseded image straight back.
        assertThat(ref.imageLocator()).isEqualTo(LOCATOR);
        assertThat(o.isResolved()).isTrue();                          // and the drift is closed
    }

    @Test
    void theADOPTED_pictureSurvivesTheEnricherRatherThanBeingOverwritten() {
        // The trap this guards: adoption goes through applyVettedProfile → MarkerImageEnricher.enrich, whose whole
        // job is to stamp imageLocator from the corpus representative for the reference's shortcode. A corpus
        // snapshot predates the drift by definition, so re-deriving here would silently restore the OLD banner.
        SupportGroupConfig c = vettedGroup();
        measuredDrift(c, LOCATOR);
        MarkerImage stored = mock(MarkerImage.class);
        when(stored.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(stored));
        // A REAL enricher, not the pass-through stub the other tests use.
        MarkerCorpusService corpus = mock(MarkerCorpusService.class);
        when(corpus.list(anyString())).thenReturn(List.of());
        SupportGroupConfigService withEnricher = new SupportGroupConfigService(
                configs, versions, drifts, new MarkerImageEnricher(corpus, imageStore), imageStore);

        SupportGroupConfig out = withEnricher.adoptDriftedMarkerImage("glowbloggeragency", UUID.randomUUID());

        VettedProfile.TypedMarkerReference ref =
                out.getVettedProfile().weeklySchedule().days().get(0).references().get(0);
        assertThat(ref.imageLocator()).isEqualTo(LOCATOR);
        assertThat(ref.source()).isEqualTo("client-drift"); // the flag the enricher honours
    }

    @Test
    void adoptingRefusesWhenThereIsNoPictureToAdopt() {
        SupportGroupConfig c = vettedGroup();
        measuredDrift(c, null);

        assertThatThrownBy(() -> service.adoptDriftedMarkerImage("glowbloggeragency", UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no picture");
    }

    @Test
    void adoptingRefusesWhenThePictureIsNoLongerStored() {
        SupportGroupConfig c = vettedGroup();
        measuredDrift(c, LOCATOR);
        when(imageStore.find(LOCATOR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adoptDriftedMarkerImage("glowbloggeragency", UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no longer stored");
    }

    @Test
    void adoptingRefusesAnObservationBelongingToAnotherGroup() {
        vettedGroup();
        DriftObservation other = DriftObservation.first(UUID.randomUUID(), DriftKind.MARKER_IMAGE_DRIFT, "d", null,
                null, null, null, 1, java.time.Instant.now());
        when(drifts.findById(any())).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.adoptDriftedMarkerImage("glowbloggeragency", UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no such drift observation");
    }

    @Test
    void adoptingRefusesWhenTheGroupHasNoVettedProfile() {
        SupportGroupConfig c = SupportGroupConfig.createRequested("glowbloggeragency");
        when(configs.findByIgAccount("glowbloggeragency")).thenReturn(Optional.of(c));
        measuredDrift(c, LOCATOR);
        MarkerImage stored = mock(MarkerImage.class);
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.adoptDriftedMarkerImage("glowbloggeragency", UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no vetted profile");
    }

    @Test
    void adoptingRefusesAnUnknownObservation() {
        vettedGroup();
        when(drifts.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adoptDriftedMarkerImage("glowbloggeragency", UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no such drift observation");
    }
}
