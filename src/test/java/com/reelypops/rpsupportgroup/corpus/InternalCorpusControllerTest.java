package com.reelypops.rpsupportgroup.corpus;

import com.reelypops.rpsupportgroup.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal corpus intake + read surface ({@code /supportgroup/v1/internal/corpus}), authenticated by the shared
 * {@code X-Internal-Api-Key}. Covers the append-only snapshot lifecycle (open → per-scroll append → seal) and the
 * admin evidence reads (list + detail), plus the guards (unknown group/snapshot, append-after-seal, validation).
 */
@SpringBootTest(properties = {"rp.internal.api-key=itest-key", "rp.corpus.retention=3"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalCorpusControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "itest-key";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MarkerCorpusService corpusService;

    @Autowired
    CorpusMaintenanceScheduler scheduler;

    private void createConfig(String ig) throws Exception {
        String body = "{\"igAccount\":\"" + ig + "\",\"definition\":{\"type\":\"CONTINUOUS\",\"timezone\":\"UTC\"}}";
        mockMvc.perform(post("/supportgroup/v1/groups")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /** Create a group then open a snapshot for it, returning the snapshot id. */
    private String openSnapshot(String ig, String source) throws Exception {
        createConfig(ig);
        MvcResult r = mockMvc.perform(post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", ig)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"" + source + "\",\"capturedByAccount\":\"scraper-acct\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.itemCount").value(0))
                .andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    /**
     * ⚠️ Real fingerprints — 64 binary digits. They used to be {@code "hash-a"} / {@code "h"}, which is the shape
     * the ingest guard now rejects, and that is the point: a fixture that cannot fail a check cannot defend it.
     * Non-hash-shaped corpus fixtures hid this whole class of fault for weeks.
     */
    private static final String HASH_A = "1010".repeat(16);
    private static final String HASH_B = "1100".repeat(16);

    private String appendBody(String shortcode, String author, String dHash, int ordinal) {
        return "{\"items\":[{\"shortcode\":\"" + shortcode + "\",\"authorUsername\":\"" + author
                + "\",\"dHash\":\"" + dHash + "\",\"postedAt\":\"2026-07-20T10:15:30Z\",\"ordinal\":" + ordinal + "}]}";
    }

    @Test
    void withoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void opensAnEmptySnapshot() throws Exception {
        String id = openSnapshot("corp-open", "REQUEST");
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.source").value("REQUEST"))
                .andExpect(jsonPath("$.snapshot.capturedByAccount").value("scraper-acct"))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void openForUnknownGroupIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", "corp-ghost")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"REQUEST\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void openWithoutSourceIsBadRequest() throws Exception {
        createConfig("corp-nosource");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", "corp-nosource")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void appendsItemsPerScroll() throws Exception {
        String id = openSnapshot("corp-append", "DUTY");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "owner1", HASH_A, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(1));
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("BBB", "member1", HASH_B, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2));
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].shortcode").value("AAA"))
                .andExpect(jsonPath("$.items[0].authorUsername").value("owner1"))
                .andExpect(jsonPath("$.items[0].postedAt").value("2026-07-20T10:15:30Z"))
                .andExpect(jsonPath("$.items[1].shortcode").value("BBB"));
    }

    @Test
    void REGRESSION_aMalformedFingerprintVOIDS_theWholeSnapshot_notJustItsOwnRow() throws Exception {
        // The user's call, 16/08/2026: "I'd rather retry the entire snapshot than risk image hash corruption sent
        // to the cloud by a single client that affects all clients after." A deep scrape builds the corpus that
        // vetted references are cut from, and those references are matched by EVERY other client — so one bad row
        // is not a bad row, it is a reference nothing can ever match, and the failure is silent.
        String id = openSnapshot("corp-poison", "DUTY");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "owner1", HASH_A, 0)))
                .andExpect(status().isOk());

        // A post SHORTCODE where the fingerprint belongs — byte-identical to the real corruption found in
        // `glowbloggeragency` on 15/08/2026, which type-checked everywhere because both are String.
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("BBB", "member1", "DbyhP29uyF5nHF-_saO60D-eic5SEtq5Qw3IAs0", 1)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                // Voided WHOLE — the earlier, perfectly good row is condemned with it.
                .andExpect(jsonPath("$.snapshot.status").value("REJECTED"))
                .andExpect(jsonPath("$.snapshot.rejectedReason").value(Matchers.containsString("malformed dHash")))
                // ...and the offending row is kept, flagged, so the fault can be READ rather than merely re-run into.
                .andExpect(jsonPath("$.items[1].unusable").value(true))
                .andExpect(jsonPath("$.items[1].unusableReason")
                        .value(Matchers.containsString("not 64 binary digits")));

        // A voided pass cannot be sealed back into usefulness.
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void BURNING_makesAPostUnselectableForever_andIsIdempotent() throws Exception {
        // The remedy for the fault that started all this: a corrupt value reached a vetted profile because an
        // operator PICKED it from a corpus tile. Repairing the profile without burning the tile fixes the
        // occurrence and leaves the fault; the same click reproduces it at the next re-vet.
        String id = openSnapshot("corp-burn", "DUTY");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "owner1", HASH_A, 0)))
                .andExpect(status().isOk());

        assertThat(corpusService.burn("corp-burn", HASH_A, "picked and found to be no fingerprint")).isEqualTo(1);
        // Already burned — burning again is a no-op, so a repeated repair does not keep rewriting rows.
        assertThat(corpusService.burn("corp-burn", HASH_A, "again")).isZero();

        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.items[0].unusable").value(true))
                .andExpect(jsonPath("$.items[0].unusableReason")
                        .value(Matchers.containsString("no fingerprint")));

        // ...and it is no longer offered as the client-computed fingerprint for that post.
        assertThat(corpusService.clientHashForPost("corp-burn", "AAA")).isEmpty();
    }

    @Test
    void burningNeedsBothAGroupAndAValue() {
        // Guards, not ceremony: a null value would match every row whose hash is null and burn the lot.
        assertThat(corpusService.burn(null, HASH_A, "r")).isZero();
        assertThat(corpusService.burn("corp-burn", null, "r")).isZero();
        assertThat(corpusService.burn("corp-burn", "  ", "r")).isZero();
    }

    @Test
    void aNULL_fingerprintIsNamedInTheRejection_notPrintedAsAnEmptyGap() throws Exception {
        // @NotBlank stops this at the HTTP boundary, so it can only arrive from another service calling in — and
        // when it does, the reason has to READ. "null" is a diagnosis; an empty gap in a sentence is a puzzle.
        //
        // The row itself cannot be filed as evidence the way a malformed-but-present hash can: {@code d_hash} is
        // NOT NULL. So the rejection reason carries the description, and the snapshot is still voided whole.
        String id = openSnapshot("corp-nullhash", "DUTY");
        UUID snapshotId = UUID.fromString(id);
        assertThatThrownBy(() -> corpusService.append(snapshotId,
                List.of(new CorpusItemPayload("AAA", "owner1", null, Instant.now(), 0))))
                .hasMessageContaining("malformed dHash")
                .hasMessageContaining("null");

        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.snapshot.status").value("REJECTED"));
    }

    @Test
    void appendToUnknownSnapshotIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", UUID.randomUUID())
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("X", "a", HASH_A, 0)))
                .andExpect(status().isNotFound());
    }

    @Test
    void appendEmptyBatchIsBadRequest() throws Exception {
        String id = openSnapshot("corp-empty", "REQUEST");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void appendInvalidItemIsBadRequest() throws Exception {
        String id = openSnapshot("corp-badit", "SCANNER");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"shortcode\":\"S\",\"authorUsername\":\"a\",\"dHash\":\"\",\"ordinal\":0}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sealsASnapshotAndBlocksFurtherAppends() throws Exception {
        String id = openSnapshot("corp-seal", "ADMIN");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "owner1", HASH_A, 0)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.sealedAt").exists());
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("BBB", "member1", HASH_B, 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void sealIsIdempotent() throws Exception {
        String id = openSnapshot("corp-seal-idem", "ADMIN");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
    }

    @Test
    void sealUnknownSnapshotIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", UUID.randomUUID())
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsAGroupsSnapshots() throws Exception {
        String ig = "corp-list";
        openSnapshot(ig, "REQUEST");
        // a second snapshot for the same group (the config already exists)
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", ig)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"DUTY\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", ig).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].source", Matchers.containsInAnyOrder("REQUEST", "DUTY")));
    }

    @Test
    void detailForUnknownSnapshotIsNotFound() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", UUID.randomUUID())
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void REGRESSION_anEmptyOPEN_snapshotDoesNotShadowTheSealedOneARepresentativeLivesIn() throws Exception {
        // THE 19/08/2026 CLEAN-SLATE FAULT, end to end. Sequence exactly as it happened live: a group is vetted
        // from an older SEALED pass while a NEWER, EMPTY, still-OPEN snapshot (a deep scrape cancelled seconds
        // after starting) sits on top of the list. The enricher used to resolve every representative against "the
        // newest snapshot" — the empty one — so no reference got a display image and every picture surface in the
        // product went dark: the client's marker strip, the re-vet cards' "vetted picture" panels, all of it.
        String sealed = openSnapshot("corp-shadow", "REQUEST");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", sealed)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "owner1", HASH_A, 0)))
                .andExpect(status().isOk());
        byte[] png = {9, 8, 7};
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", sealed, "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_PNG).content(png))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", sealed).header(KEY_HEADER, KEY))
                .andExpect(status().isOk());

        // The cancelled scrape: newer, OPEN, zero items, zero representatives.
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", "corp-shadow")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"REQUEST\",\"capturedByAccount\":\"scraper\"}"))
                .andExpect(status().isCreated());

        // The cross-snapshot lookup falls straight through the empty OPEN pass to the sealed one.
        assertThat(corpusService.findRepresentative("corp-shadow", "AAA"))
                .hasValueSatisfying(rep -> assertThat(rep.getImage()).containsExactly(9, 8, 7));
        // ...and a shortcode nobody ever captured stays honestly empty.
        assertThat(corpusService.findRepresentative("corp-shadow", "NOPE")).isEmpty();
        // Guards, not ceremony — same rule as burn(): a null/blank key must answer empty, never scan.
        assertThat(corpusService.findRepresentative(null, "AAA")).isEmpty();
        assertThat(corpusService.findRepresentative("corp-shadow", " ")).isEmpty();
    }

    @Test
    void putsAndServesARepresentative() throws Exception {
        String id = openSnapshot("corp-rep", "REQUEST");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "owner1", HASH_A, 0)))
                .andExpect(status().isOk());
        byte[] png = {1, 2, 3, 4, 5};
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_PNG).content(png))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png));
        // the detail view reports which shortcodes carry a representative
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.representativeShortcodes.length()").value(1))
                .andExpect(jsonPath("$.representativeShortcodes[0]").value("AAA"));
    }

    @Test
    void ocrRepresentativeReturns200ForAStoredImage() throws Exception {
        String id = openSnapshot("corp-ocr", "REQUEST");
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_PNG).content(new byte[]{1, 2, 3}))
                .andExpect(status().isNoContent());

        // The AI gateway is off in tests, so the read fails open to a null ocrText (omitted from the JSON by NON_NULL) —
        // the endpoint still returns 200. The actual OCR text path is covered by MarkerOcrServiceTest.
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}/ocr", id, "AAA")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk());
    }

    @Test
    void ocrUnknownRepresentativeIsNotFound() throws Exception {
        String id = openSnapshot("corp-ocr-missing", "REQUEST");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}/ocr", id, "ZZZ")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void representativeUploadUpsertsBytes() throws Exception {
        String id = openSnapshot("corp-rep-upsert", "DUTY");
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_JPEG).content(new byte[]{1, 1}))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_PNG).content(new byte[]{9, 9, 9}))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{9, 9, 9}));
    }

    @Test
    void missingRepresentativeIsNotFound() throws Exception {
        String id = openSnapshot("corp-rep-none", "REQUEST");
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "ZZZ")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void putRepresentativeForUnknownSnapshotIsNotFound() throws Exception {
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}",
                        UUID.randomUUID(), "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_PNG).content(new byte[]{1}))
                .andExpect(status().isNotFound());
    }

    @Test
    void putEmptyRepresentativeIsBadRequest() throws Exception {
        String id = openSnapshot("corp-rep-empty", "REQUEST");
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_PNG).content(new byte[0]))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retentionPrunesToTheConfiguredWindowOnSeal() throws Exception {
        String ig = "corp-prune";
        createConfig(ig);
        // open + seal 4 passes; the test window is rp.corpus.retention=3, so the oldest is pruned on the 4th seal
        for (int i = 0; i < 4; i++) {
            MvcResult r = mockMvc.perform(post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", ig)
                            .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"source\":\"DUTY\"}"))
                    .andExpect(status().isCreated()).andReturn();
            String sid = JsonPath.read(r.getResponse().getContentAsString(), "$.id");
            mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", sid).header(KEY_HEADER, KEY))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", ig).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    /**
     * <strong>QUIET, NOT OLD.</strong>
     *
     * <p>The sweep measured from when a pass was OPENED, which forced a six-hour window — a deep scrape
     * legitimately runs that long, and cutting a live one short would void real work. So a pass whose machine
     * died two minutes in stayed OPEN for the rest of those six hours, looking exactly like one still
     * streaming, while the console beside it already knew the machine had stopped reporting (operator,
     * 23/09/2026). A live pass appends the whole time it is alive, so silence decides where age cannot.</p>
     */
    @Test
    void sweepLeavesAPassThatIsSTILLBEINGWRITTENTO_howeverOldItIs() throws Exception {
        String id = openSnapshot("corp-sweep-live", "ADMIN");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "a", HASH_A, 0)))
                .andExpect(status().isOk());

        // A cutoff a minute in the PAST: the pass was opened before it, but written to after — so it is alive.
        // Under the old age-based rule this pass would have been swept out from under a running scrape.
        corpusService.sweepStale(Instant.now().minusSeconds(60));

        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.snapshot.status").value("OPEN"));
    }

    /** And one that never received a single item is swept on the same rule, not left needing a second one. */
    @Test
    void sweepCatchesAPassThatNeverReceivedAnything() throws Exception {
        String id = openSnapshot("corp-sweep-empty", "ADMIN");

        corpusService.sweepStale(Instant.now().plusSeconds(60));

        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.snapshot.status").value("INTERRUPTED"));
    }

    @Test
    void sweepStaleMarksOrphanedOpenSnapshotsInterrupted() throws Exception {
        String id = openSnapshot("corp-sweep", "REQUEST");
        // a cutoff in the future ⇒ every currently-open snapshot is treated as stale and swept
        corpusService.sweepStale(Instant.now().plusSeconds(60));
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.status").value("INTERRUPTED"));
        // A swept pass is NO LONGER terminal, and that is the point of the change above it: the sweep is a
        // guess, and an arriving item is proof the guess was wrong. This used to answer 409, which on
        // 23/09/2026 threw away 1429 posts of a live six-hour scrape the sweeper had written off four hours
        // in. The pass reopens and keeps what it is given.
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("X", "a", HASH_A, 0)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.snapshot.status").value("OPEN"));
    }

    @Test
    void scheduledSweepLeavesFreshSnapshotsOpen() throws Exception {
        String id = openSnapshot("corp-sched", "REQUEST");
        scheduler.sweep(); // default 6h stale TTL ⇒ a fresh open snapshot is untouched
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.status").value("OPEN"));
    }

    // ── CANCELLED: a person stopped it (23/09/2026) ─────────────────────────────────────────────────

    /**
     * <strong>A CANCELLATION IS REPORTED, NOT INFERRED.</strong>
     *
     * <p>Before this, a scrape a person stopped left its pass at OPEN until the stale sweeper reached it six
     * hours later and called it INTERRUPTED — which reads as "the client stopped talking", and is not what
     * happened. For those six hours an abandoned pass looked exactly like one still streaming, so an operator
     * who had pressed Stop could not see their own decision anywhere.</p>
     */
    @Test
    void CANCELLING_recordsThatAPersonStoppedIt_ratherThanLeavingItOpen() throws Exception {
        String id = openSnapshot("corp-cancel", "ADMIN");

        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/cancel", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.status").value("CANCELLED"));
    }

    /**
     * The inbox that carries a stop is at-least-once, so a repeat must not rewrite the outcome — and a stop
     * that lost its race to the seal has nothing to cancel. Neither is an error: the scrape ended either way.
     */
    @Test
    void CANCELLING_neverRewritesAPassThatAlreadyEnded() throws Exception {
        String sealed = openSnapshot("corp-cancel-late", "ADMIN");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", sealed).header(KEY_HEADER, KEY))
                .andExpect(status().isOk());

        // The stop arrives after the pass finished: it finds nothing to cancel and says so by leaving it alone.
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/cancel", sealed).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));

        String cancelled = openSnapshot("corp-cancel-twice", "ADMIN");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/cancel", cancelled).header(KEY_HEADER, KEY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/cancel", cancelled).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    /** An unknown snapshot is a 404 here as everywhere else on this surface. */
    @Test
    void CANCELLING_anUnknownSnapshotIs404() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/cancel",
                        java.util.UUID.randomUUID()).header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    // ── DELETING one by hand (23/09/2026) ───────────────────────────────────────────────────────────

    /**
     * <strong>RETENTION ONLY RUNS ON A SEAL.</strong>
     *
     * <p>A group whose passes keep being interrupted or cancelled never prunes, so they pile up with no way
     * to clear one (operator: "old snapshots seem to build up. we need a way for me to remove them
     * manually"). The items go with the snapshot by the cascade the schema already declares — the same one
     * automatic retention relies on, so this carries no risk that prune was not already taking.</p>
     */
    @Test
    void DELETING_removesTheSnapshotAndEverythingItHolds() throws Exception {
        String id = openSnapshot("corp-delete", "ADMIN");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "member1", "1010".repeat(16), 0)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
        // ...and it is gone from the group's evidence list, not merely unreachable by id.
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", "corp-delete")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * An OPEN pass can go too, and that is the point rather than an oversight: a machine killed mid-scrape
     * leaves one open for six hours until the stale sweeper reaches it, and that is exactly the row somebody
     * wants rid of. Refusing would sound careful and would strand them.
     */
    @Test
    void DELETING_worksOnAPassThatIsStillOpen() throws Exception {
        String id = openSnapshot("corp-delete-open", "ADMIN");

        mockMvc.perform(delete("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());
    }

    /** Already gone is 404 — a second click is not a new kind of failure, and the wish is granted either way. */
    @Test
    void DELETING_somethingAlreadyGoneIs404() throws Exception {
        mockMvc.perform(delete("/supportgroup/v1/internal/corpus/snapshots/{id}",
                        java.util.UUID.randomUUID()).header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    // ── An inference yields to evidence (23/09/2026) ────────────────────────────────────────────────

    /**
     * <strong>A SWEEP IS A GUESS. AN ARRIVING ITEM IS PROOF.</strong>
     *
     * <p>The sweeper concluded a live six-hour scrape had died four hours in, because a pause outlasted the
     * silence timer. Every page after that was refused with a 409, the final seal was a no-op, and 712 posts
     * of real work were discarded — all on an inference that nothing was allowed to correct.</p>
     */
    @Test
    void anAppendREOPENSaPassTheSweeperHadGivenUpOn() throws Exception {
        String id = openSnapshot("corp-revive", "ADMIN");
        corpusService.sweepStale(Instant.now().plusSeconds(60));
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.snapshot.status").value("INTERRUPTED"));

        // The scrape was alive all along, and says so the only way it can.
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "a", HASH_A, 0)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.snapshot.status").value("OPEN"))
                .andExpect(jsonPath("$.snapshot.itemCount").value(1))
                // and it can finish properly, which a swept pass never could
                .andExpect(jsonPath("$.snapshot.sealedAt").doesNotExist());

        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
    }

    /**
     * Only the GUESS gives way. A pass somebody stopped, one the client finished, and one voided for a bad
     * fingerprint were all DECIDED — a late arrival must not overturn a decision.
     */
    @Test
    void anAppendDoesNotREOPENaPassThatSomebodyENDED() throws Exception {
        String cancelled = openSnapshot("corp-revive-cancelled", "ADMIN");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/cancel", cancelled)
                .header(KEY_HEADER, KEY)).andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", cancelled)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "a", HASH_A, 0)))
                .andExpect(status().isConflict());

        String sealed = openSnapshot("corp-revive-sealed", "ADMIN");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", sealed)
                .header(KEY_HEADER, KEY)).andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", sealed)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("BBB", "a", HASH_A, 0)))
                .andExpect(status().isConflict());
    }
}
