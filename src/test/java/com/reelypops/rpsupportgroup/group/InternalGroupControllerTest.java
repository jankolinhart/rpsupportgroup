package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.TestcontainersConfiguration;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal SG-config surface ({@code /supportgroup/v1/internal/groups}): reads authenticated by the shared
 * {@code X-Internal-Api-Key}. Without the key the internal chain rejects with 401; with it the scanner / BFF
 * can read the same authoritative configs the client sees.
 */
@SpringBootTest(properties = "rp.internal.api-key=itest-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalGroupControllerTest {

    private static final String KEY = "itest-key";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MarkerImageRepository markerImages;

    @Autowired
    SupportGroupConfigService configService;

    @Autowired
    SupportGroupConfigRepository configRepository;

    @Autowired
    MarkerImageStore markerImageStore;

    private void createConfig(String ig) throws Exception {
        String body = "{\"igAccount\":\"" + ig + "\",\"definition\":{\"type\":\"CONTINUOUS\",\"timezone\":\"UTC\"}}";
        mockMvc.perform(post("/supportgroup/v1/groups")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void withoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/groups"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withKeyListsConfigs() throws Exception {
        createConfig("int-list");
        mockMvc.perform(get("/supportgroup/v1/internal/groups").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void withKeyGetsConfig() throws Exception {
        createConfig("int-get");
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "int-get").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.igAccount").value("int-get"))
                .andExpect(jsonPath("$.definition.type").value("CONTINUOUS"));
    }

    @Test
    void withKeyGetUnknownIsNotFound() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "int-none").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void servesAStoredMarkerImageByLocator() throws Exception {
        markerImages.save(MarkerImage.create("mi-loc-abc", new byte[]{1, 2, 3}, MediaType.IMAGE_PNG_VALUE));
        mockMvc.perform(get("/supportgroup/v1/internal/groups/marker-images/{loc}", "mi-loc-abc").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void unknownMarkerImageLocatorIsNotFound() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/groups/marker-images/{loc}", "mi-none").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void withKeyCreatesUnclaimedConfig() throws Exception {
        String body = "{\"igAccount\":\"int-create\",\"definition\":{\"type\":\"SINGLE_MARKER\",\"timezone\":\"UTC\","
                + "\"openWeekdays\":[1,2,3,4,5]},\"description\":\"Weekday single-marker group.\"}";
        mockMvc.perform(post("/supportgroup/v1/internal/groups").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.igAccount").value("int-create"))
                .andExpect(jsonPath("$.status").value("UNCLAIMED"))
                .andExpect(jsonPath("$.vetted").value(false))
                .andExpect(jsonPath("$.adminAttributed").value(false))
                .andExpect(jsonPath("$.description").value("Weekday single-marker group."))
                .andExpect(jsonPath("$.definition.openWeekdays.length()").value(5));
    }

    @Test
    void intakeExistingReturnsOk() throws Exception {
        String body = "{\"igAccount\":\"int-intake-dup\",\"definition\":{\"type\":\"CONTINUOUS\",\"timezone\":\"UTC\"}}";
        mockMvc.perform(post("/supportgroup/v1/internal/groups").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/internal/groups").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.igAccount").value("int-intake-dup"));
    }

    @Test
    void vettingWithoutADefinitionIsConflict() throws Exception {
        // A name-only request (no definition) lands UNDER_VERIFICATION; it cannot be vetted until a definition is set.
        mockMvc.perform(post("/supportgroup/v1/internal/groups").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"igAccount\":\"int-req-novet\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vettingState").value("UNDER_VERIFICATION"))
                .andExpect(jsonPath("$.definition").doesNotExist());
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vet", "int-req-novet").header(KEY_HEADER, KEY))
                .andExpect(status().isConflict());
    }

    @Test
    void withKeyAttributesConfigToOwner() throws Exception {
        createConfig("int-attr");
        UUID owner = UUID.randomUUID();
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/attribute", "int-attr").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":\"" + owner + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.ownerId").value(owner.toString()))
                .andExpect(jsonPath("$.adminAttributed").value(true));
    }

    @Test
    void attributeUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/attribute", "int-attr-none").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void withKeyVetsConfig() throws Exception {
        createConfig("int-vet");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vet", "int-vet").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.igAccount").value("int-vet"))
                .andExpect(jsonPath("$.vetted").value(true))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void vetIsIdempotent() throws Exception {
        createConfig("int-vet-idem");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vet", "int-vet-idem").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vet", "int-vet-idem").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vetted").value(true))
                .andExpect(jsonPath("$.version").value(2)); // re-vet does not bump the version
    }

    @Test
    void vetUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vet", "int-vet-none").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void removingAConfigWithDependentRowsCascades() throws Exception {
        // Regression (migration 017, 2026-08-11): a config with a child row referencing it — drift_observation (015)
        // or vetted_profile_version (014) — used to FK-violate on delete because those FKs lacked ON DELETE CASCADE,
        // so the admin SG-config DELETE returned 502. Seed a drift_observation, then the delete must CASCADE (204),
        // not 502, and the config must be gone.
        createConfig("int-del-cascade");
        configService.recordDrift("int-del-cascade", DriftKind.MARKER_DISAGREE, "dev-1", null, null, 1, 2, 3);

        mockMvc.perform(delete("/supportgroup/v1/internal/groups/{ig}", "int-del-cascade").header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "int-del-cascade").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void createStartsUnderVerification() throws Exception {
        createConfig("int-vs-new");
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "int-vs-new").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vettingState").value("UNDER_VERIFICATION"))
                .andExpect(jsonPath("$.vetted").value(false));
    }

    @Test
    void vetMovesToVettedState() throws Exception {
        createConfig("int-vs-vet");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vet", "int-vs-vet").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vettingState").value("VETTED"))
                .andExpect(jsonPath("$.vetted").value(true));
    }

    @Test
    void rejectsConfigWithReasonAndCooldown() throws Exception {
        createConfig("int-reject");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/reject", "int-reject").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"below follower threshold\",\"cooldownDays\":14}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vettingState").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("below follower threshold"))
                .andExpect(jsonPath("$.cooldownUntil").isNotEmpty())
                .andExpect(jsonPath("$.rejectedAt").isNotEmpty())
                .andExpect(jsonPath("$.vetted").value(false))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void rejectInvalidBodyIsBadRequest() throws Exception {
        createConfig("int-reject-bad");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/reject", "int-reject-bad").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cooldownDays\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/reject", "int-reject-none").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\",\"cooldownDays\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void blocksConfig() throws Exception {
        createConfig("int-block");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/block", "int-block").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vettingState").value("BLOCKED"))
                .andExpect(jsonPath("$.vetted").value(false))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void blockIsIdempotent() throws Exception {
        createConfig("int-block-idem");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/block", "int-block-idem").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/block", "int-block-idem").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vettingState").value("BLOCKED"))
                .andExpect(jsonPath("$.version").value(2)); // re-block does not bump the version
    }

    @Test
    void blockUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/block", "int-block-none").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectingABlockedConfigIsConflict() throws Exception {
        createConfig("int-block-reject");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/block", "int-block-reject").header(KEY_HEADER, KEY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/reject", "int-block-reject").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\",\"cooldownDays\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void vettingABlockedConfigIsConflict() throws Exception {
        createConfig("int-block-vet");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/block", "int-block-vet").header(KEY_HEADER, KEY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vet", "int-block-vet").header(KEY_HEADER, KEY))
                .andExpect(status().isConflict());
    }

    @Test
    void withKeyUpdatesDefinition() throws Exception {
        createConfig("int-upd");
        String body = "{\"definition\":{\"type\":\"TWO_MARKER\",\"timezone\":\"Europe/Berlin\","
                + "\"startMarkerTime\":\"08:00\",\"endMarkerTime\":\"20:00\",\"openWeekdays\":[1,2,3,4,5]},"
                + "\"description\":\"A lovely two-marker group.\"}";
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}", "int-upd").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.type").value("TWO_MARKER"))
                .andExpect(jsonPath("$.definition.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.definition.startMarkerTime").value("08:00"))
                .andExpect(jsonPath("$.definition.openWeekdays[0]").value(1))
                .andExpect(jsonPath("$.definition.openWeekdays.length()").value(5))
                .andExpect(jsonPath("$.description").value("A lovely two-marker group."))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void continuousCorrectionKeepsOpenWeekdaysAndContinuousDaysInSync() throws Exception {
        createConfig("int-cont-sync");
        // A CONTINUOUS correction that sets openWeekdays should back-write continuousDays (the client's round-reset
        // still reads continuousDays) — R-1 canonicalisation keeps the two in sync.
        String body = "{\"definition\":{\"type\":\"CONTINUOUS\",\"timezone\":\"UTC\",\"openWeekdays\":[1,2,3,4,5]}}";
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}", "int-cont-sync").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.openWeekdays.length()").value(5))
                .andExpect(jsonPath("$.definition.continuousDays.length()").value(5))
                .andExpect(jsonPath("$.definition.continuousDays[0]").value(1));
    }

    @Test
    void continuousCorrectionBackfillsOpenWeekdaysFromContinuousDays() throws Exception {
        createConfig("int-cont-backfill");
        // A legacy CONTINUOUS correction that sets only continuousDays should back-fill openWeekdays so the tile can
        // render its opening days.
        String body = "{\"definition\":{\"type\":\"CONTINUOUS\",\"timezone\":\"UTC\",\"continuousDays\":[6,0]}}";
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}", "int-cont-backfill").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.openWeekdays.length()").value(2))
                .andExpect(jsonPath("$.definition.openWeekdays[0]").value(6))
                .andExpect(jsonPath("$.definition.continuousDays[1]").value(0));
    }

    @Test
    void updateDefinitionUnknownConfigIsNotFound() throws Exception {
        String body = "{\"definition\":{\"type\":\"CONTINUOUS\",\"timezone\":\"UTC\"}}";
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}", "int-upd-none").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateDefinitionInvalidIsBadRequest() throws Exception {
        createConfig("int-upd-bad");
        String body = "{\"definition\":{\"timezone\":\"UTC\"}}"; // missing @NotNull type
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}", "int-upd-bad").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withKeyRemovesConfig() throws Exception {
        createConfig("int-del");
        mockMvc.perform(delete("/supportgroup/v1/internal/groups/{ig}", "int-del").header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "int-del").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(delete("/supportgroup/v1/internal/groups/{ig}", "int-del-none").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void contributeThenServeAvatarRoundTrips() throws Exception {
        byte[] png = new byte[] { 1, 2, 3, 4 };
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/avatar", "int-av")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_JPEG).content(png))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/avatar", "int-av").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(png));
    }

    @Test
    void contributeAvatarUpdatesExisting() throws Exception {
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/avatar", "int-av2")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_JPEG).content(new byte[] { 1 }))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/avatar", "int-av2")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_PNG).content(new byte[] { 9, 9 }))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/avatar", "int-av2").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[] { 9, 9 }));
    }

    @Test
    void serveAvatarUnknownIsNotFound() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/avatar", "int-av-none").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void contributeEmptyAvatarIsBadRequest() throws Exception {
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/avatar", "int-av-empty")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_JPEG).content(new byte[0]))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contributeAvatarWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/avatar", "int-av-x")
                        .contentType(MediaType.IMAGE_JPEG).content(new byte[] { 1 }))
                .andExpect(status().isUnauthorized());
    }

    // --- content categories (Cycle 12) ---

    @Test
    void assignsAndUnassignsACategory() throws Exception {
        createConfig("int-cat");
        // assign is idempotent — twice, then it appears once on the config
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/categories/{slug}", "int-cat", "travel")
                        .header(KEY_HEADER, KEY)).andExpect(status().isNoContent());
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/categories/{slug}", "int-cat", "travel")
                        .header(KEY_HEADER, KEY)).andExpect(status().isNoContent());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "int-cat").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", Matchers.contains("travel")));

        mockMvc.perform(delete("/supportgroup/v1/internal/groups/{ig}/categories/{slug}", "int-cat", "travel")
                        .header(KEY_HEADER, KEY)).andExpect(status().isNoContent());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "int-cat").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(0));
    }

    @Test
    void unassigningAnAbsentCategoryIsANoOp() throws Exception {
        createConfig("int-cat-noop");
        mockMvc.perform(delete("/supportgroup/v1/internal/groups/{ig}/categories/{slug}", "int-cat-noop", "fashion")
                        .header(KEY_HEADER, KEY)).andExpect(status().isNoContent());
    }

    @Test
    void assignToUnknownGroupIsNotFound() throws Exception {
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/categories/{slug}", "int-cat-none", "travel")
                        .header(KEY_HEADER, KEY)).andExpect(status().isNotFound());
    }

    @Test
    void assignUnknownCategoryIsNotFound() throws Exception {
        createConfig("int-cat-badcat");
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/categories/{slug}", "int-cat-badcat", "no-such")
                        .header(KEY_HEADER, KEY)).andExpect(status().isNotFound());
    }

    @Test
    void browseReturnsPagedVettedWithCategoryFilter() throws Exception {
        createConfig("int-br-a");
        createConfig("int-br-b");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vet", "int-br-a").header(KEY_HEADER, KEY)).andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vet", "int-br-b").header(KEY_HEADER, KEY)).andExpect(status().isOk());
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/categories/{slug}", "int-br-a", "travel").header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/supportgroup/v1/internal/groups/browse")
                        .param("categories", "travel").param("q", "int-br").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].igAccount").value("int-br-a"))
                .andExpect(jsonPath("$.content[0].categories", Matchers.contains("travel")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(get("/supportgroup/v1/internal/groups/browse").param("q", "int-br").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    // --- M3a: the vetted profile (single authoritative confirmed object) + the definition projection ---

    private static final String VETTED_BODY =
            "{\"definition\":{\"type\":\"TWO_MARKER\",\"timezone\":\"Europe/Berlin\",\"markerOwners\":[\"ras.circle\"],"
                    + "\"startMarkerTime\":\"08:00\",\"endMarkerTime\":\"20:00\",\"openWeekdays\":[1,2,3,4,5]},"
                    + "\"detector\":{\"style\":\"FLAT_BANNER\",\"references\":[{\"markerType\":\"start\","
                    + "\"dHashes\":[\"1010101010101010101010101010101010101010101010101010101010101010\"],\"matchThreshold\":4}]},\"description\":\"Dailyblogger group.\"}";

    @Test
    void saveVettedProfileProjectsToDefinitionWithoutVetting() throws Exception {
        createConfig("m3a-save"); // CONTINUOUS/UTC, UNDER_VERIFICATION, version 1

        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "m3a-save")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vetted").value(false))                    // Save must NOT vet
                .andExpect(jsonPath("$.vettingState").value("UNDER_VERIFICATION"))
                .andExpect(jsonPath("$.definition.type").value("TWO_MARKER"))     // round-truth projected onto definition
                .andExpect(jsonPath("$.definition.markerOwners[0]").value("ras.circle"))
                .andExpect(jsonPath("$.description").value("Dailyblogger group."))
                .andExpect(jsonPath("$.version").value(2));                       // bumped by the projection
    }

    @Test
    void vetVettedProfileSavesAndFlipsToVetted() throws Exception {
        createConfig("m3a-vetnow");

        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vetted-profile/vet", "m3a-vetnow")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vetted").value(true))
                .andExpect(jsonPath("$.vettingState").value("VETTED"))
                .andExpect(jsonPath("$.definition.type").value("TWO_MARKER"));
    }

    @Test
    void getCarriesTheActiveSnapshotsChangeNote() throws Exception {
        createConfig("m3a-cn");
        // First snapshot (nothing to diff against → empty change-note).
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "m3a-cn")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY))
                .andExpect(status().isOk());
        // Second snapshot changes only the description → the now-active snapshot's change-note is that diff (M5.3b).
        String updated = VETTED_BODY.replace("Dailyblogger group.", "Updated blurb.");
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "m3a-cn")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(updated))
                .andExpect(status().isOk());

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "m3a-cn").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeNote[0].field").value("description"))
                .andExpect(jsonPath("$.changeNote[0].from").value("Dailyblogger group."))
                .andExpect(jsonPath("$.changeNote[0].to").value("Updated blurb."));
    }

    @Test
    void getReturnsAnEmptyChangeNoteWhenThereIsNoActiveSnapshot() throws Exception {
        createConfig("m3a-cn-none"); // no vetted profile yet → no active snapshot
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "m3a-cn-none").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changeNote").isEmpty());
    }

    @Test
    void vetVettedProfileOnBlockedConfigIsConflict() throws Exception {
        createConfig("m3a-blocked");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/block", "m3a-blocked").header(KEY_HEADER, KEY))
                .andExpect(status().isOk());

        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vetted-profile/vet", "m3a-blocked")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void saveVettedProfileWithInvalidDefinitionIsBadRequest() throws Exception {
        // definition present but missing the required type → bean validation rejects before the service runs.
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "m3a-bad")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":{\"timezone\":\"UTC\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void saveVettedProfileUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "m3a-none")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void getVettingProfilesReturnsBothSlots() throws Exception {
        createConfig("m3a-profiles");
        // before any save: both slots empty
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/vetting-profiles", "m3a-profiles")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detected").doesNotExist())
                .andExpect(jsonPath("$.vetted").doesNotExist());
        // after a Save the vetted slot is populated (round-trips through jsonb)
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "m3a-profiles")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/vetting-profiles", "m3a-profiles")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vetted.definition.type").value("TWO_MARKER"))
                .andExpect(jsonPath("$.vetted.detector.style").value("FLAT_BANNER"))
                .andExpect(jsonPath("$.vetted.detector.references[0].markerType").value("start"))
                .andExpect(jsonPath("$.detected").doesNotExist());
    }

    // --- M5 A2: versioned vetted profile (append-only history) + rollback / mode kill switches ---

    /** A second, DIFFERENT vetted profile so the SAVE produces a non-empty change-note vs the prior active snapshot. */
    private static final String VETTED_BODY_V2 =
            "{\"definition\":{\"type\":\"TWO_MARKER\",\"timezone\":\"Europe/Berlin\",\"markerOwners\":[\"ras.circle\"],"
                    + "\"startMarkerTime\":\"09:00\",\"endMarkerTime\":\"20:00\",\"openWeekdays\":[1,2,3,4,5]},"
                    + "\"detector\":{\"style\":\"FLAT_BANNER\",\"references\":[{\"markerType\":\"start\","
                    + "\"dHashes\":[\"1010101010101010101010101010101010101010101010101010101010101010\"],\"matchThreshold\":6}]},\"description\":\"Updated blogger group.\"}";

    @Test
    void firstSaveOpensVersionOneWithEmptyChangeNote() throws Exception {
        createConfig("a2-v1");
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "a2-v1")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSnapshotVersion").value(1))
                .andExpect(jsonPath("$.mode").value("LIKING"));           // default mode surfaced on the config

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/vetted-profile/versions", "a2-v1")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSnapshotVersion").value(1))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].snapshotVersion").value(1))
                .andExpect(jsonPath("$.versions[0].active").value(true))
                .andExpect(jsonPath("$.versions[0].changeNote.length()").value(0));   // first snapshot: nothing to diff
    }

    @Test
    void secondSaveAppendsVersionTwoWithFieldLevelChangeNote() throws Exception {
        createConfig("a2-v2");
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "a2-v2")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "a2-v2")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY_V2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSnapshotVersion").value(2));

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/vetted-profile/versions", "a2-v2")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSnapshotVersion").value(2))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].snapshotVersion").value(2))       // newest first
                .andExpect(jsonPath("$.versions[0].active").value(true))
                .andExpect(jsonPath("$.versions[1].snapshotVersion").value(1))
                .andExpect(jsonPath("$.versions[1].active").value(false))
                .andExpect(jsonPath("$.versions[0].changeNote.length()").value(Matchers.greaterThanOrEqualTo(1)))
                // structured {field, from, to}: the description changed old → new
                .andExpect(jsonPath("$.versions[0].changeNote[?(@.field=='description')].to")
                        .value(Matchers.hasItem("Updated blogger group.")));
    }

    @Test
    void rollbackReactivatesPriorSnapshotWithoutAppendingHistory() throws Exception {
        createConfig("a2-rb");
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "a2-rb")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY))
                .andExpect(status().isOk());                                          // v1: "Dailyblogger group."
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "a2-rb")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY_V2))
                .andExpect(status().isOk());                                          // v2 active: "Updated blogger group."

        // Kill switch: roll back to v1 → repoints the active snapshot, restores its projection, bumps the ETag, no new row.
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vetted-profile/rollback/{v}", "a2-rb", 1)
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSnapshotVersion").value(1))
                .andExpect(jsonPath("$.description").value("Dailyblogger group."));   // v1 projection restored

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/vetted-profile/versions", "a2-rb")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSnapshotVersion").value(1))
                .andExpect(jsonPath("$.versions.length()").value(2))                  // history untouched
                .andExpect(jsonPath("$.versions[?(@.snapshotVersion==1)].active").value(Matchers.hasItem(true)))
                .andExpect(jsonPath("$.versions[?(@.snapshotVersion==2)].active").value(Matchers.hasItem(false)));
    }

    @Test
    void rollbackToUnknownSnapshotIsNotFound() throws Exception {
        createConfig("a2-rb404");
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "a2-rb404")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vetted-profile/rollback/{v}", "a2-rb404", 99)
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void rollbackUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vetted-profile/rollback/{v}", "a2-none-rb", 1)
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionsUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/vetted-profile/versions", "a2-none-v")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void setModeFlipsAndIsIdempotent() throws Exception {
        createConfig("a2-mode");                                                      // version 1, LIKING
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "a2-mode").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("LIKING"))
                .andExpect(jsonPath("$.version").value(1));

        // Flip to PAUSED → mode changes + version bumps so clients adopt it.
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/mode/{mode}", "a2-mode", "PAUSED")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PAUSED"))
                .andExpect(jsonPath("$.version").value(2));

        // Flip to PAUSED AGAIN → idempotent: mode stays, version does NOT bump.
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/mode/{mode}", "a2-mode", "PAUSED")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PAUSED"))
                .andExpect(jsonPath("$.version").value(2));                           // unchanged (setMode returned false)

        // A different mode bumps again.
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/mode/{mode}", "a2-mode", "SCRAPE_ONLY")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SCRAPE_ONLY"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void setModeUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/mode/{mode}", "a2-mode-none", "PAUSED")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    // --- M5 B1 prerequisite: the client-facing GroupResponse ships the per-weekday schedule (plan P5) ---

    @Test
    void getConfigExposesPerWeekdayScheduleFromTheVettedProfile() throws Exception {
        createConfig("m5-weekly");
        // Save a vetted profile carrying a per-weekday schedule — a Monday CROSS_DAY round (end marker a day later).
        String body = "{\"definition\":{\"type\":\"TWO_MARKER\",\"timezone\":\"Europe/Berlin\",\"markerOwners\":[\"ras.circle\"],"
                + "\"startMarkerTime\":\"20:00\",\"endMarkerTime\":\"02:00\",\"endMarkerDayOffset\":1,\"openWeekdays\":[1]},"
                + "\"detector\":{\"style\":\"FLAT_BANNER\",\"references\":[{\"markerType\":\"start\",\"dHashes\":[\"1010101010101010101010101010101010101010101010101010101010101010\"],\"matchThreshold\":4}]},"
                + "\"description\":\"Nightly group.\","
                + "\"weeklySchedule\":{\"days\":[{\"weekday\":1,\"open\":true,\"type\":\"TWO_MARKER\","
                + "\"startMarkerTime\":\"20:00\",\"endMarkerTime\":\"02:00\",\"endMarkerDayOffset\":1,\"style\":\"FLAT_BANNER\","
                + "\"references\":[{\"markerType\":\"start\",\"dHashes\":[\"1010101010101010101010101010101010101010101010101010101010101010\"],\"ocrText\":\"START\",\"matchThreshold\":4}]}]}}";
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "m5-weekly")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "m5-weekly").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklySchedule.days[0].weekday").value(1))
                .andExpect(jsonPath("$.weeklySchedule.days[0].open").value(true))
                .andExpect(jsonPath("$.weeklySchedule.days[0].type").value("TWO_MARKER"))
                .andExpect(jsonPath("$.weeklySchedule.days[0].endMarkerDayOffset").value(1))
                .andExpect(jsonPath("$.weeklySchedule.days[0].roundClass").value("CROSS_DAY")) // A3-derived, now shipped
                .andExpect(jsonPath("$.weeklySchedule.days[0].references[0].markerType").value("start"))
                .andExpect(jsonPath("$.weeklySchedule.days[0].references[0].ocrText").value("START"));
    }

    // --- M5 re-vet consumer: drift ingest → derived needs-re-vet + nominations ---

    @Test
    void driftRaisesNeedsRevetAndAggregatesTheReason() throws Exception {
        createConfig("drift-md");
        String user = UUID.randomUUID().toString();

        // First reporter observes a marker-disagree drift.
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-md").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"dev-1\",\"reporterUserId\":\"" + user
                                + "\",\"agreePass\":5,\"disagreePass\":2,\"persistenceCount\":4}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "drift-md").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsRevet").value(true))
                .andExpect(jsonPath("$.revetReasons[0].kind").value("MARKER_DISAGREE"))
                .andExpect(jsonPath("$.revetReasons[0].distinctReporters").value(1))
                .andExpect(jsonPath("$.revetReasons[0].totalOccurrences").value(1))
                .andExpect(jsonPath("$.revetReasons[0].latestAgreePass").value(5))
                .andExpect(jsonPath("$.revetReasons[0].latestDisagreePass").value(2))
                .andExpect(jsonPath("$.revetReasons[0].maxPersistenceCount").value(4))
                .andExpect(jsonPath("$.revetReasons[0].firstSeenAt").exists())
                .andExpect(jsonPath("$.revetReasons[0].lastSeenAt").exists());

        // Same reporter reports again → occurrence bumps, reporter count stays 1.
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-md").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"dev-1\",\"agreePass\":6,"
                                + "\"disagreePass\":1,\"persistenceCount\":9}"))
                .andExpect(status().isAccepted());
        // A second, corroborating reporter.
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-md").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"dev-2\",\"agreePass\":4,"
                                + "\"disagreePass\":3,\"persistenceCount\":2}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "drift-md").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsRevet").value(true))
                .andExpect(jsonPath("$.revetReasons[0].distinctReporters").value(2))
                .andExpect(jsonPath("$.revetReasons[0].totalOccurrences").value(3))     // dev-1 twice + dev-2 once
                .andExpect(jsonPath("$.revetReasons[0].maxPersistenceCount").value(9));  // dev-1's refreshed tally
    }

    @Test
    void newOwnerDriftFlagsRevetAndSurfacesTheNomination() throws Exception {
        createConfig("drift-nom");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-nom").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NEW_OWNER\",\"reporterDeviceId\":\"dev-1\",\"nominatedOwnerHandle\":\"cand.owner\"}"))
                .andExpect(status().isAccepted());

        // M5.20: a new-owner nomination now flags the config for re-vet, with a NEW_OWNER reason carrying the handle…
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "drift-nom").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsRevet").value(true))
                .andExpect(jsonPath("$.revetReasons[0].kind").value("NEW_OWNER"))
                .andExpect(jsonPath("$.revetReasons[0].nominatedOwnerHandles[0]").value("cand.owner"))
                .andExpect(jsonPath("$.revetReasons[0].distinctReporters").value(1));
        // …and it surfaces on the review-candidate nominations view.
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/nominations", "drift-nom").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("NEW_OWNER"))
                .andExpect(jsonPath("$[0].nominatedOwnerHandle").value("cand.owner"))
                .andExpect(jsonPath("$[0].reporterDeviceId").value("dev-1"))
                .andExpect(jsonPath("$[0].occurrenceCount").value(1))
                .andExpect(jsonPath("$[0].resolved").value(false));
    }

    @Test
    void confirmNominationAddsTheOwnerBumpsVersionAndClearsTheReVet() throws Exception {
        createConfig("nom-confirm");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "nom-confirm").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NEW_OWNER\",\"reporterDeviceId\":\"dev-1\",\"nominatedOwnerHandle\":\"new.owner\"}"))
                .andExpect(status().isAccepted());

        // Add: the handle joins the vetted marker owners; the nomination clears; needsRevet drops.
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/nominations/{h}/confirm", "nom-confirm", "new.owner")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.markerOwners[0]").value("new.owner"))
                .andExpect(jsonPath("$.needsRevet").value(false));
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/nominations", "nom-confirm").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void dismissNominationClearsTheReVetWithoutAddingAnOwner() throws Exception {
        createConfig("nom-dismiss");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "nom-dismiss").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NEW_OWNER\",\"reporterDeviceId\":\"dev-1\",\"nominatedOwnerHandle\":\"not.owner\"}"))
                .andExpect(status().isAccepted());

        // Dismiss: the nomination clears + needsRevet drops, but the owner set is untouched.
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/nominations/{h}/dismiss", "nom-dismiss", "not.owner")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsRevet").value(false));
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/nominations", "nom-dismiss").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void vettingResolvesOpenMarkerDisagreeObservations() throws Exception {
        createConfig("drift-vet");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-vet").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"dev-1\",\"agreePass\":5,\"disagreePass\":2}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "drift-vet").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.needsRevet").value(true));

        // A re-vet clears the derived flag.
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vet", "drift-vet").header(KEY_HEADER, KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "drift-vet").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.needsRevet").value(false))
                .andExpect(jsonPath("$.revetReasons").isEmpty());

        // A fresh drift after the re-vet re-opens the flag.
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-vet").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"dev-1\",\"agreePass\":4,\"disagreePass\":4}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "drift-vet").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.needsRevet").value(true))
                .andExpect(jsonPath("$.revetReasons[0].totalOccurrences").value(2));    // same row, occurrence bumped
    }

    @Test
    void vetVettedProfileResolvesOpenMarkerDisagreeObservations() throws Exception {
        createConfig("drift-vp");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-vp").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"dev-1\",\"agreePass\":5,\"disagreePass\":2}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vetted-profile/vet", "drift-vp")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(VETTED_BODY))
                .andExpect(status().isOk());

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "drift-vp").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.needsRevet").value(false));
    }

    @Test
    void newOwnerDriftWithoutAHandleIsBadRequest() throws Exception {
        createConfig("drift-nohandle");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-nohandle").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"NEW_OWNER\",\"reporterDeviceId\":\"dev-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void driftWithoutAReporterDeviceIsBadRequest() throws Exception {
        createConfig("drift-nodev");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-nodev").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void driftForUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-none").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"dev-1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nominationsForUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/nominations", "drift-nom-none").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void driftWithoutTheKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-401")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"dev-1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listExposesTheDerivedNeedsRevetFlag() throws Exception {
        createConfig("drift-list");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-list").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"dev-1\",\"agreePass\":5,\"disagreePass\":2}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/supportgroup/v1/internal/groups").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.igAccount=='drift-list')].needsRevet").value(Matchers.hasItem(true)));
    }

    @Test
    void acknowledgeClearsNeedsRevetWithoutRevettingOrBumpingTheEtag() throws Exception {
        createConfig("drift-ack");                                                    // version 1, not vetted
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-ack").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"dev-1\",\"agreePass\":5,\"disagreePass\":2}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "drift-ack").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.needsRevet").value(true))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.vetted").value(false));

        // Acknowledge: clears the flag WITHOUT vetting or bumping the ETag.
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift/acknowledge", "drift-ack").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsRevet").value(false))
                .andExpect(jsonPath("$.revetReasons").isEmpty())
                .andExpect(jsonPath("$.version").value(1))                            // no ETag bump — config not re-shipped
                .andExpect(jsonPath("$.vetted").value(false));                        // and NOT vetted

        // The cleared state persists…
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "drift-ack").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.needsRevet").value(false))
                .andExpect(jsonPath("$.version").value(1));

        // …but a fresh drift re-raises it.
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-ack").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_DISAGREE\",\"reporterDeviceId\":\"dev-1\",\"agreePass\":4,\"disagreePass\":4}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "drift-ack").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.needsRevet").value(true));
    }

    @Test
    void acknowledgeForAnUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift/acknowledge", "drift-ack-none").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    // --- MEASURED banner drift → one-click adoption of the newer picture (16/08/2026) ---

    @Test
    void adoptingADriftedBannerADDS_thePictureToTheVettedProfile() throws Exception {
        createConfig("drift-adopt");
        String old = "1010101010101010101010101010101010101010101010101010101010101010";
        String body = "{\"definition\":{\"type\":\"TWO_MARKER\",\"timezone\":\"Europe/Paris\",\"markerOwners\":[\"glow\"],"
                + "\"startMarkerTime\":\"20:31\",\"endMarkerTime\":\"20:31\",\"endMarkerDayOffset\":0,\"openWeekdays\":[0]},"
                + "\"description\":\"Glow.\","
                + "\"weeklySchedule\":{\"days\":[{\"weekday\":0,\"open\":true,\"type\":\"TWO_MARKER\","
                + "\"startMarkerTime\":\"20:31\",\"endMarkerTime\":\"20:31\",\"endMarkerDayOffset\":0,\"style\":\"TEXT_OVERLAY\","
                + "\"references\":[{\"markerType\":\"start\",\"dHashes\":[\"" + old + "\"],"
                + "\"ocrText\":\"GB AGENCY START Sonntag\",\"matchThreshold\":4}]}]}}";
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "drift-adopt")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // The client reports MEASURED drift and delivers the picture the owner is posting NOW (directive B1: the
        // cloud never fetches from Instagram, so this is the only way that picture can arrive).
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "drift-adopt").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_IMAGE_DRIFT\",\"reporterDeviceId\":\"dev-1\",\"markerRole\":\"start\","
                                + "\"imageDistance\":15,\"imageThreshold\":10,\"evidencePostId\":\"DcEj0SRu\","
                                // ⚠️ The CLIENT's fingerprint of the delivered picture, and the ONLY one adoptable.
                                // Hashing these same bytes in the cloud lands 15–34 bits away while clients match
                                // at 4–10, so an adopted reference minted here is unmatchable by every client that
                                // receives it — a silent failure that reports success.
                                + "\"evidenceImageHash\":\"" + CLIENT_HASH + "\","
                                + "\"evidenceImage\":\"" + pngBase64() + "\"}"))
                .andExpect(status().isAccepted());

        // The admin sees the measurement AND a locator to fetch the picture with.
        String obsId = JsonPath.read(mockMvc.perform(
                        get("/supportgroup/v1/internal/groups/{ig}/drift", "drift-adopt").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("MARKER_IMAGE_DRIFT"))
                .andExpect(jsonPath("$[0].imageDistance").value(15))
                .andExpect(jsonPath("$[0].imageThreshold").value(10))
                .andExpect(jsonPath("$[0].evidencePostId").value("DcEj0SRu"))
                .andExpect(jsonPath("$[0].evidenceImageLocator").isNotEmpty())
                .andReturn().getResponse().getContentAsString(), "$[0].id");

        // One click: ADD the new picture. The old hash STAYS — it is what lets the client's calibration see that
        // the two roles are close and tighten its own threshold floor.
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift/{id}/adopt-image", "drift-adopt", obsId)
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk());

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "drift-adopt").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.weeklySchedule.days[0].references[0].dHashes.length()").value(2))
                .andExpect(jsonPath("$.weeklySchedule.days[0].references[0].dHashes[0]").value(old))
                .andExpect(jsonPath("$.weeklySchedule.days[0].references[0].dHashes[1]").value(CLIENT_HASH));
    }

    @Test
    void clientDeliveredPicturesAreFILED_DURABLY_andSurviveTheDriftReportThatCarriedThem() throws Exception {
        // Directive B1: no cloud service ever contacts Instagram, so a picture a client sends is the ONLY copy
        // there will ever be. Drift observations are a work queue — resolved, then pruned — and if the picture went
        // with them, a later re-vet would need a whole duty scrape to see a banner the system had already been
        // shown. So it is filed the moment it arrives, with the client's own fingerprint attached.
        createConfig("client-catalogue");
        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "client-catalogue").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_IMAGE_DRIFT\",\"reporterDeviceId\":\"dev-1\","
                                + "\"markerRole\":\"start\",\"markerText\":\"GB AGENCY START Sonntag\","
                                + "\"imageDistance\":15,\"imageThreshold\":10,\"evidencePostId\":\"DcEj0SRu\","
                                + "\"evidenceImageHash\":\"" + CLIENT_HASH + "\","
                                + "\"evidenceImage\":\"" + pngBase64() + "\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/client-marker-images", "client-catalogue")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dHash").value(CLIENT_HASH))
                .andExpect(jsonPath("$[0].markerRole").value("start"))
                .andExpect(jsonPath("$[0].markerText").value("GB AGENCY START Sonntag"))
                .andExpect(jsonPath("$[0].evidencePostId").value("DcEj0SRu"))
                .andExpect(jsonPath("$[0].imageLocator").isNotEmpty())
                .andExpect(jsonPath("$[0].timesSeen").value(1));
    }

    @Test
    void theSAME_pictureReportedRepeatedlyIsONE_candidate_counted() throws Exception {
        // A client re-asserts an unresolved drift every 60 s until the cloud acknowledges it. Fifty reports of one
        // banner is one banner an operator can pick, not fifty identical tiles — but how routine it is matters, so
        // the count is kept.
        createConfig("client-dedup");
        String body = "{\"kind\":\"MARKER_IMAGE_DRIFT\",\"reporterDeviceId\":\"dev-1\",\"markerRole\":\"start\","
                + "\"markerText\":\"GB AGENCY START Sonntag\","
                + "\"imageDistance\":15,\"imageThreshold\":10,\"evidencePostId\":\"DcEj0SRu\","
                + "\"evidenceImageHash\":\"" + CLIENT_HASH + "\",\"evidenceImage\":\"" + pngBase64() + "\"}";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "client-dedup").header(KEY_HEADER, KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/client-marker-images", "client-dedup")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].timesSeen").value(3))
                // The LATEST sighting describes it — a banner's role and text can be re-read on any pass, and the
                // most recent reading is the one an operator should be choosing from.
                .andExpect(jsonPath("$[0].markerText").value("GB AGENCY START Sonntag"));
    }

    /** A fingerprint a CLIENT computed — the only dialect any repair or adoption may write. */
    private static final String CLIENT_HASH = "1100".repeat(16);

    // --- CORRUPT reference → one-click repair by COPYING the client's fingerprint for that post (18/08/2026) ---

    @Test
    void REPAIRING_fallsBackToTheCorpusWhenNoClientHasSentALivePicture() throws Exception {
        createConfig("ref-repair");
        String locator = markerImageStore.capture(java.util.Base64.getDecoder().decode(pngBase64())).orElseThrow();
        String shortcode = "DbyhP29uyF5nHF-_saO60D-eic5SEtq5Qw3IAs0"; // the real corrupt value from the live record

        // Saved past the API on purpose: ingest validation now REFUSES a profile like this, so a group carrying one
        // was stored before that guard — and cannot be fixed through the Vetting Portal, because every Save 400s.
        // This is exactly the state `glowbloggeragency` is in.
        SupportGroupConfig c = configRepository.findByIgAccount("ref-repair").orElseThrow();
        GroupDefinition def = new GroupDefinition(SgType.TWO_MARKER, "Europe/Paris", java.util.List.of("glow"),
                null, null, "20:31", "20:31", 0, null, null, null, null, null, null);
        VettedProfile.TypedMarkerReference corrupt = new VettedProfile.TypedMarkerReference("start",
                java.util.List.of(shortcode), "GB AGENCY START Sonntag", 4, "detected", shortcode, null, locator);
        DayDefinition sunday = new DayDefinition(0, true, SgType.TWO_MARKER, "20:31", "20:31", 0, -1, null,
                "23:59", 0, "10:30", 1, 1, MarkerStyle.TEXT_OVERLAY, java.util.List.of(corrupt));
        c.saveVettedProfile(new VettedProfile(def, null, "Glow.", new WeeklyScheduleDefinition(
                java.util.List.of(sunday))), 1L);
        configRepository.save(c);

        // ⚠️ THE FALLBACK PATH — no client has reported a live picture for this reference, so there is no
        // image+fingerprint pair to adopt. Only then is the corpus consulted, and only because the corrupt value
        // IS the reference's own post shortcode, so the damage happens to name a post a client once streamed a
        // fingerprint for. That is coincidence, not design: snapshots are pruned, passes get voided, rows get
        // burned, and the corpus row may even be a different RENDITION of the same banner (glow's full image and
        // its thumbnail measure 15 bits apart, against a 4–10 bit match tolerance).
        //
        // What it is NOT is a computation. Hashing the picture here — what this endpoint did until 18/08/2026 —
        // produces a value 15–34 bits from what clients measure: well-formed, plausible, shipped to everyone, and
        // matched by no one.
        String snapshotId = JsonPath.read(mockMvc.perform(
                        post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", "ref-repair")
                                .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"source\":\"DUTY\",\"capturedByAccount\":\"scraper\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", snapshotId)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"shortcode\":\"" + shortcode + "\",\"authorUsername\":\"glow\","
                                + "\"dHash\":\"" + CLIENT_HASH + "\",\"ordinal\":0}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vetted-profile/repair-hashes", "ref-repair")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk());

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "ref-repair").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.weeklySchedule.days[0].references[0].dHashes.length()").value(1))
                // A client-computed value VERBATIM — not merely something hash-shaped. Asserting only the shape is
                // what let a wrong-dialect hash pass for a repair for a whole day.
                .andExpect(jsonPath("$.weeklySchedule.days[0].references[0].dHashes[0]").value(CLIENT_HASH));
    }

    @Test
    void repairingAProfileWithNothingWrongIsAConflict_notASilentNoOp() throws Exception {
        createConfig("ref-repair-clean");
        String good = "1010101010101010101010101010101010101010101010101010101010101010";
        String body = "{\"definition\":{\"type\":\"TWO_MARKER\",\"timezone\":\"Europe/Paris\",\"markerOwners\":[\"glow\"],"
                + "\"startMarkerTime\":\"20:31\",\"endMarkerTime\":\"20:31\",\"endMarkerDayOffset\":0,\"openWeekdays\":[0]},"
                + "\"description\":\"Glow.\","
                + "\"weeklySchedule\":{\"days\":[{\"weekday\":0,\"open\":true,\"type\":\"TWO_MARKER\","
                + "\"startMarkerTime\":\"20:31\",\"endMarkerTime\":\"20:31\",\"endMarkerDayOffset\":0,\"style\":\"TEXT_OVERLAY\","
                + "\"references\":[{\"markerType\":\"start\",\"dHashes\":[\"" + good + "\"],"
                + "\"ocrText\":\"START\",\"matchThreshold\":4}]}]}}";
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/vetted-profile", "ref-repair-clean")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/vetted-profile/repair-hashes", "ref-repair-clean")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isConflict());
    }

    @Test
    void aCorruptReferenceIsReportableByTheClientAndSurfacesAsAReVetReason() throws Exception {
        createConfig("ref-corrupt-report");

        mockMvc.perform(post("/supportgroup/v1/internal/groups/{ig}/drift", "ref-corrupt-report")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MARKER_REFERENCE_CORRUPT\",\"reporterDeviceId\":\"dev-1\","
                                + "\"markerRole\":\"start\",\"markerText\":\"GB AGENCY START Sonntag\","
                                + "\"detail\":\"value=DbyhP29u… looks like an Instagram post shortcode\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}/drift", "ref-corrupt-report").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$[0].kind").value("MARKER_REFERENCE_CORRUPT"))
                .andExpect(jsonPath("$[0].markerText").value("GB AGENCY START Sonntag"))
                .andExpect(jsonPath("$[0].detail").value(Matchers.containsString("shortcode")));

        // The notification level: it must reach the needs-re-vet surface, not merely be stored.
        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "ref-corrupt-report").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.needsRevet").value(true))
                .andExpect(jsonPath("$.revetReasons[0].kind").value("MARKER_REFERENCE_CORRUPT"));
    }

    /** A real, decodable PNG as base64 — ImageDHash refuses anything it cannot decode, so a stub will not do. */
    private static String pngBase64() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, (x * 16) << 16 | (y * 16) << 8);
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static final String KEY_HEADER = "X-Internal-Api-Key";
}
