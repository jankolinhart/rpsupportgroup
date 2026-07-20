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

    private static final String KEY_HEADER = "X-Internal-Api-Key";
}
