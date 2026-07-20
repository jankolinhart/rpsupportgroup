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
        String body = "{\"igAccount\":\"int-create\",\"definition\":{\"type\":\"SINGLE_MARKER\",\"timezone\":\"UTC\"}}";
        mockMvc.perform(post("/supportgroup/v1/internal/groups").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.igAccount").value("int-create"))
                .andExpect(jsonPath("$.status").value("UNCLAIMED"))
                .andExpect(jsonPath("$.vetted").value(false))
                .andExpect(jsonPath("$.adminAttributed").value(false));
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
    void withKeyUpdatesDefinition() throws Exception {
        createConfig("int-upd");
        String def = "{\"type\":\"TWO_MARKER\",\"timezone\":\"Europe/Berlin\","
                + "\"startMarkerTime\":\"08:00\",\"endMarkerTime\":\"20:00\"}";
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}", "int-upd").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(def))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.type").value("TWO_MARKER"))
                .andExpect(jsonPath("$.definition.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.definition.startMarkerTime").value("08:00"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void updateDefinitionUnknownConfigIsNotFound() throws Exception {
        String def = "{\"type\":\"CONTINUOUS\",\"timezone\":\"UTC\"}";
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}", "int-upd-none").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(def))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateDefinitionInvalidIsBadRequest() throws Exception {
        createConfig("int-upd-bad");
        String def = "{\"timezone\":\"UTC\"}"; // missing @NotNull type
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}", "int-upd-bad").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(def))
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

    private static final String KEY_HEADER = "X-Internal-Api-Key";
}
