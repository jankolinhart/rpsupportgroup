package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.TestcontainersConfiguration;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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

    private static final String KEY_HEADER = "X-Internal-Api-Key";
}
