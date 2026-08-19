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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The CORRUPT-reference half of the re-vet loop: the client discovers it, the cloud raises it as a re-vet reason,
 * and one click repairs it by COPYING a fingerprint a client computed.
 *
 * <p>Separate from {@link DriftImageAdoptionTest} because the fault and the remedy are different in kind. Drift is a
 * stale-but-real picture, remedied by ADDING the newer one. A corrupt reference holds a value that is not a hash at
 * all — it matches nothing, contradicts nothing, and silently distorts the client's threshold calibration.</p>
 *
 * <p><strong>⚠️ The repair used to recompute the hash in the cloud (fixed 18/08/2026).</strong> That was a second
 * silent fault on top of the first: this service's Java hasher and the client's sharp/libvips one land 15–34 bits
 * apart on identical bytes, and clients accept a match at 4–10 — so the repaired reference was well-formed,
 * plausible, shipped to every client, and unmatchable by all of them. Note where the fingerprint comes from here:
 * the reference's corrupt value IS its own post shortcode, so the deep-scrape corpus already holds a
 * client-computed hash for exactly that post. The damage names its own repair.</p>
 */
class CorruptReferenceRepairTest {

    private static final String SHORTCODE = "DbyhP29uyF5nHF-_saO60D-eic5SEtq5Qw3IAs0";
    private static final String GOOD = "1010".repeat(16);
    private static final String LOCATOR = "27d823d2";
    /** What a CLIENT computed for the reference's own post, streamed during a deep scrape. */
    private static final String CORPUS_HASH = "1100".repeat(16);
    /** What a CLIENT computed for the live banner it delivered with its report. */
    private static final String LIVE_HASH = "1111000011110000".repeat(4);

    private final SupportGroupConfigRepository configs = mock(SupportGroupConfigRepository.class);
    private final VettedProfileVersionRepository versions = mock(VettedProfileVersionRepository.class);
    private final DriftObservationRepository drifts = mock(DriftObservationRepository.class);
    private final MarkerImageEnricher enricher = mock(MarkerImageEnricher.class);
    private final MarkerImageStore imageStore = mock(MarkerImageStore.class);
    private final ClientMarkerImageRepository clientImages = mock(ClientMarkerImageRepository.class);
    private final MarkerCorpusService corpusService = mock(MarkerCorpusService.class);
    private final SupportGroupConfigService service =
            new SupportGroupConfigService(configs, versions, drifts, enricher, imageStore, clientImages, corpusService);

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
        // The corpus holds a client-computed fingerprint for the reference's post — the repair copies it.
        when(corpusService.clientHashForPost("glowbloggeragency", SHORTCODE))
                .thenReturn(Optional.of(CORPUS_HASH));
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
    void FALLBACK_repairsFromTheCorpusWhenNoClientHasSentALivePicture_andClosesTheDrift() {
        // The report carries no picture — an older client, or a capture that could not be retained. Only then is
        // the corpus consulted, and only because the corrupt value IS the reference's own post shortcode, so the
        // damage happens to name a post a client once streamed a fingerprint for. Coincidence, not design.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        DriftObservation open = openCorruptDrift(c);
        MarkerImage stored = mock(MarkerImage.class);
        when(stored.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(stored));

        SupportGroupConfig out = service.repairMalformedReferenceHashes("glowbloggeragency");

        List<String> hashes = out.getVettedProfile().weeklySchedule().days().get(0).references().get(0).dHashes();
        // The CLIENT's value, verbatim — not a well-formed one minted here, which would look identical to any
        // assertion that only checks the shape and would match nothing in the field.
        assertThat(hashes).containsExactly(CORPUS_HASH);
        assertThat(open.isResolved()).isTrue();
    }

    @Test
    void REGRESSION_theClientsLIVE_pictureBEATS_theCorpusWhenBothAreAvailable() {
        // ⚠️ THE ORDER IS THE POINT, and it was wrong first time round.
        //
        // The corpus describes the OLD post; the client's report describes what the marker looks like TODAY, and
        // arrives as a matched image+fingerprint pair. Preferring the corpus would repair the reference to a
        // picture nobody is posting any more — technically valid, and a wasted opportunity to make the profile
        // current from data we were handed for free. There is also no guarantee a corpus row exists at all
        // (snapshots are pruned, passes get voided, rows get burned), so it cannot be the default.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        DriftObservation open = openCorruptDrift(c);
        open.measure("start", "GB AGENCY START Sonntag", null, null, "DcEj0SRu", "live-loc", "shortcode",
                LIVE_HASH);
        MarkerImage vetted = mock(MarkerImage.class);
        when(vetted.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(vetted));
        MarkerImage live = mock(MarkerImage.class);
        when(live.getImage()).thenReturn(differentPng());
        when(imageStore.find("live-loc")).thenReturn(Optional.of(live));
        // Both sources can answer. Only one may be used.
        when(corpusService.clientHashForPost("glowbloggeragency", SHORTCODE))
                .thenReturn(Optional.of(CORPUS_HASH));

        SupportGroupConfig out = service.repairMalformedReferenceHashes("glowbloggeragency");

        var ref = out.getVettedProfile().weeklySchedule().days().get(0).references().get(0);
        assertThat(ref.dHashes()).containsExactly(LIVE_HASH);
        assertThat(ref.dHashes()).doesNotContain(CORPUS_HASH);
        assertThat(ref.imageLocator()).isEqualTo("live-loc");
        assertThat(open.isResolved()).isTrue();
    }

    @Test
    void REPAIR_alsoBURNS_theCorpusRowThatSuppliedTheCorruptValue() {
        // Fixing the profile is only half of it. The corrupt value was PICKED, in the Vetting Portal, from a corpus
        // tile — and repairing the profile leaves that tile sitting there, clickable, ready to write the same value
        // straight back at the next re-vet. The user's requirement, 18/08/2026: it "never can be clicked and added
        // to the vetted profile again".
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        openCorruptDrift(c);
        MarkerImage stored = mock(MarkerImage.class);
        when(stored.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(stored));

        service.repairMalformedReferenceHashes("glowbloggeragency");

        verify(corpusService).burn(eq("glowbloggeragency"), eq(SHORTCODE), anyString());
    }

    @Test
    void REPAIR_TAKES_theClientsLiveBanner_pictureAndHashTogether() {
        // ⚠️ THE CLIENT'S LIVE PAIR IS THE REPAIR — not a bonus added on top of a corpus lookup.
        //
        // A client reporting a corrupt reference sends the banner the owner is posting TODAY together with its own
        // fingerprint of it. The two describe the same bytes and describe what clients actually have to recognise
        // now, so taking them makes the reference BETTER rather than merely well-formed again. The corpus is a
        // fallback for when no client has sent one — it holds the OLD post, its presence is coincidence (snapshots
        // are pruned), and its row may even be a different RENDITION of the same banner: measured 16/08/2026,
        // glow's full post image and its thumbnail are 15 bits apart while clients match at 4–10.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        DriftObservation open = openCorruptDrift(c);
        open.measure("start", "GB AGENCY START Sonntag", null, null, "DcEj0SRu", "live-loc", "shortcode",
                LIVE_HASH);
        MarkerImage vetted = mock(MarkerImage.class);
        when(vetted.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(vetted));
        MarkerImage live = mock(MarkerImage.class);
        when(live.getImage()).thenReturn(differentPng());
        when(imageStore.find("live-loc")).thenReturn(Optional.of(live));

        SupportGroupConfig out = service.repairMalformedReferenceHashes("glowbloggeragency");

        var ref = out.getVettedProfile().weeklySchedule().days().get(0).references().get(0);
        assertThat(ref.dHashes()).containsExactly(LIVE_HASH);   // the client's live fingerprint, verbatim
        assertThat(ref.dHashes()).doesNotContain(SHORTCODE);    // the junk is gone
        assertThat(ref.dHashes()).doesNotContain(CORPUS_HASH);  // ...and the corpus was not consulted at all
        // The PICTURE follows the hash. A reference whose fingerprint is the live banner's while its picture still
        // shows the superseded one is how an operator ends up adopting a change and then being shown the old image.
        assertThat(ref.imageLocator()).isEqualTo("live-loc");
        assertThat(open.isResolved()).isTrue();
    }

    @Test
    void repairDoesNotDUPLICATE_whenTheLiveBannerIsTheSamePicture() {
        // Identical pictures need no second entry — the decision the operator would otherwise have to make.
        // "Identical" is now decided by the fingerprint the CLIENT computed, not by re-hashing two byte arrays
        // here: the same picture yields the same client hash, so equality is a comparison rather than a guess.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        DriftObservation open = openCorruptDrift(c);
        open.measure("start", "GB AGENCY START Sonntag", null, null, "DcEj0SRu", "live-loc", "shortcode",
                CORPUS_HASH);
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
        corrupt.measure("start", "GB AGENCY START Sonntag", null, null, "DcEj0SRu", "live-loc", "shortcode",
                LIVE_HASH);
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
        // The observation names a picture that is no longer in the store (retention, a cleared cache). Its HASH is
        // still the client's fingerprint of what is being posted today, so it is still the right value to write —
        // the fingerprint is what matching needs, the picture is only what a human looks at. The reference keeps
        // its old display image rather than pointing at bytes we no longer hold.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        DriftObservation open = openCorruptDrift(c);
        open.measure("start", "GB AGENCY START Sonntag", null, null, "DcEj0SRu", "gone-loc", "shortcode",
                LIVE_HASH);
        MarkerImage vetted = mock(MarkerImage.class);
        when(vetted.getImage()).thenReturn(png());
        when(imageStore.find(LOCATOR)).thenReturn(Optional.of(vetted));
        when(imageStore.find("gone-loc")).thenReturn(Optional.empty());

        SupportGroupConfig out = service.repairMalformedReferenceHashes("glowbloggeragency");

        var ref = out.getVettedProfile().weeklySchedule().days().get(0).references().get(0);
        assertThat(ref.dHashes()).containsExactly(LIVE_HASH);
        assertThat(ref.imageLocator()).isEqualTo(LOCATOR);  // unchanged — we do not point at bytes we lost
        assertThat(open.isResolved()).isTrue();
    }

    @Test
    void REGRESSION_aSTALE_corruptionReportCannotReOpenAFixedFault() {
        // Live on 16/08, one minute after a successful repair: the client re-reported against the snapshot it had
        // not yet re-pulled, observeAgain re-opened the resolved observation, and the group went back to "needs
        // re-vet" over a fault that no longer existed — with no way out, because Repair then finds nothing
        // malformed and answers 409. The cloud holds the authoritative profile, so it checks rather than believes.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, GOOD)); // already repaired
        java.time.Instant now = java.time.Instant.now();
        DriftObservation stale = DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "dev-1", null,
                null, null, null, 1, now);
        stale.measure("start", "GB AGENCY START Sonntag", null, null, null, null, "looks like a shortcode");
        when(drifts.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(
                c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT)).thenReturn(List.of(stale));
        when(drifts.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        DriftObservation out = service.recordDrift("glowbloggeragency", DriftKind.MARKER_REFERENCE_CORRUPT, "dev-1",
                null, null, null, null, 1, "start", "GB AGENCY START Sonntag", "looks like a shortcode",
                null, null, null, new byte[0]);

        assertThat(out).isNull();                 // not raised
        assertThat(stale.isResolved()).isTrue();  // and the one it left behind is closed
    }

    @Test
    void aGENUINE_corruptionReportIsStillRaised() {
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        when(drifts.findForReference(any(), any(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        when(drifts.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(any(), any())).thenReturn(List.of());
        when(drifts.save(any())).thenAnswer(i -> i.getArgument(0));

        DriftObservation out = service.recordDrift("glowbloggeragency", DriftKind.MARKER_REFERENCE_CORRUPT, "dev-1",
                null, null, null, null, 1, "start", "GB AGENCY START Sonntag", "looks like a shortcode",
                null, null, null, new byte[0]);

        assertThat(out).isNotNull();
        assertThat(c).isNotNull();
    }

    @Test
    void anUNRECOGNISED_referenceIsBelieved_ratherThanContradicted() {
        // We only contradict a client when we can positively see the fault is gone — never when we simply cannot
        // find what it is talking about.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, GOOD));
        when(drifts.findForReference(any(), any(), anyString(), any(), any()))
                .thenReturn(Optional.empty());
        when(drifts.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(any(), any())).thenReturn(List.of());
        when(drifts.save(any())).thenAnswer(i -> i.getArgument(0));

        DriftObservation out = service.recordDrift("glowbloggeragency", DriftKind.MARKER_REFERENCE_CORRUPT, "dev-1",
                null, null, null, null, 1, "start", "SOME OTHER BANNER", "looks like a shortcode",
                null, null, null, new byte[0]);

        assertThat(out).isNotNull();
        assertThat(c).isNotNull();
    }

    @Test
    void refusesWhenEveryReferenceIsAlreadyWellFormed() {
        SupportGroupConfig c = groupWith(ref("start", "START", LOCATOR, GOOD));
        when(drifts.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(
                c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT)).thenReturn(List.of());

        assertThatThrownBy(() -> service.repairMalformedReferenceHashes("glowbloggeragency"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nothing to repair");
    }

    @Test
    void REGRESSION_repairCLEARS_anObservationThatOutlivedItsFault() {
        // An observation can outlive the fault: a client on a stale snapshot re-opens a resolved one, then adopts
        // the fix and never reports it again — so the ingest guard, which only fires on a report, never runs. The
        // group was left stuck with Repair answering 409 and Acknowledge covering only marker-disagree. Live on
        // `glowbloggeragency` (16/08/2026). The button now clears what it can instead of refusing.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, GOOD)); // already fine
        java.time.Instant now = java.time.Instant.now();
        DriftObservation stuck = DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "dev-1",
                null, null, null, null, 1, now);
        stuck.measure("start", "GB AGENCY START Sonntag", null, null, null, null, "looks like a shortcode");
        when(drifts.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(
                c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT)).thenReturn(List.of(stuck));
        when(drifts.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        SupportGroupConfig out = service.repairMalformedReferenceHashes("glowbloggeragency");

        assertThat(stuck.isResolved()).isTrue();
        assertThat(out).isNotNull();
    }

    @Test
    void repairStillREFUSES_whenTheObservationDescribesARealFault() {
        // Only observations the profile CONTRADICTS are cleared — a genuine one must not be swept away.
        SupportGroupConfig c = groupWith(ref("start", "GB AGENCY START Sonntag", null, SHORTCODE));
        java.time.Instant now = java.time.Instant.now();
        DriftObservation real = DriftObservation.first(c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT, "dev-1",
                null, null, null, null, 1, now);
        real.measure("start", "GB AGENCY START Sonntag", null, null, null, null, "looks like a shortcode");
        // ⚠️ Nothing on record from any client: the scrape pass that carried this post has been pruned, and no
        // live picture was ever delivered. That is the ONLY condition under which repair now fails — and it must
        // fail loudly, because the alternative (minting a hash here) succeeds loudly and matches nothing.
        when(corpusService.clientHashForPost(anyString(), any())).thenReturn(Optional.empty());

        when(drifts.findByConfigIdAndKindAndResolvedFalseOrderByLastSeenAtDesc(
                c.getId(), DriftKind.MARKER_REFERENCE_CORRUPT)).thenReturn(List.of(real));

        assertThatThrownBy(() -> service.repairMalformedReferenceHashes("glowbloggeragency"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nothing could be repaired");
        assertThat(real.isResolved()).isFalse();
    }

    @Test
    void refusesAndNAMES_theReferenceWhenNoClientHasEverFingerprintedIt() {
        // "Nothing happened" must never be silent — the administrator has to learn that this one needs a re-vet.
        groupWith(ref("start", "GB AGENCY START Sonntag", LOCATOR, SHORTCODE));
        when(imageStore.find(LOCATOR)).thenReturn(Optional.empty());
        // ⚠️ Nothing on record from any client: the scrape pass that carried this post has been pruned, and no
        // live picture was ever delivered. That is the ONLY condition under which repair now fails — and it must
        // fail loudly, because the alternative (minting a hash here) succeeds loudly and matches nothing.
        when(corpusService.clientHashForPost(anyString(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.repairMalformedReferenceHashes("glowbloggeragency"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nothing could be repaired")
                .hasMessageContaining("GB AGENCY START Sonntag")
                .hasMessageContaining("needs a re-vet");
    }

    @Test
    void refusesWhenNoClientFingerprintExistsForTheReferenceAtAll() {
        // A reference vetted from an operator upload, or hand-authored: no client has ever seen this picture, so
        // there is no fingerprint anyone could match it by, and the image store must not even be consulted —
        // hashing the bytes here is precisely the move that produced an unmatchable "repair".
        groupWith(ref("start", "START Sonntag", null, SHORTCODE));
        // ⚠️ Nothing on record from any client: the scrape pass that carried this post has been pruned, and no
        // live picture was ever delivered. That is the ONLY condition under which repair now fails — and it must
        // fail loudly, because the alternative (minting a hash here) succeeds loudly and matches nothing.
        when(corpusService.clientHashForPost(anyString(), any())).thenReturn(Optional.empty());

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
        corrupt.measure("start", "GB AGENCY START Sonntag", null, null, "DcEj0SRu", "live-loc",
                "looks like a shortcode", LIVE_HASH);
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
        // ⚠️ BOTH in the CLIENT's dialect. Hashing the two pictures here instead would give a self-consistent
        // pair 15–34 bits from what every client measures — so the distance an administrator reads while deciding
        // "same banner or a different one?" would be a number no client would ever produce.
        assertThat(v.referenceImageHash()).isEqualTo(CORPUS_HASH);    // what a repair would write
        assertThat(v.evidenceImageHash()).isEqualTo(LIVE_HASH);       // what adopting would write
        assertThat(v.storedValue()).isEqualTo(SHORTCODE);             // what is stored today — the fault itself
        assertThat(DriftObservationResponse.of(v).storedValue()).isEqualTo(SHORTCODE);
        assertThat(DriftObservationResponse.of(v).evidenceImageHash()).isEqualTo(LIVE_HASH);
    }

    @Test
    void recordingACorruptReferenceStoresTheFaultAndTheReferenceItBelongsTo() {
        SupportGroupConfig c = groupWith(ref("start", "START", LOCATOR, GOOD));
        when(drifts.findForReference(any(), any(), anyString(), any(), any()))
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
