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

    private static final String KEY_HEADER = "X-Internal-Api-Key";
}
