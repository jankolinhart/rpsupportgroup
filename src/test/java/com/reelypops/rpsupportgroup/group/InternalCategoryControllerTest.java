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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal content-category admin surface ({@code /supportgroup/v1/internal/categories}): master-list curation
 * behind the shared {@code X-Internal-Api-Key}. Covers list / create / delete (cascade off groups + affected count)
 * and the auth boundary.
 */
@SpringBootTest(properties = "rp.internal.api-key=itest-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalCategoryControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "itest-key";

    @Autowired
    MockMvc mockMvc;

    private void createConfig(String ig) throws Exception {
        String body = "{\"igAccount\":\"" + ig + "\",\"definition\":{\"type\":\"CONTINUOUS\",\"timezone\":\"UTC\"}}";
        mockMvc.perform(post("/supportgroup/v1/internal/groups").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void createCategory(String slug, String label) throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/categories").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"" + slug + "\",\"label\":\"" + label + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void withoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/categories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listsSeededCategories() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/categories").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug", Matchers.hasItems("travel", "fashion", "photography")));
    }

    @Test
    void createsANewCategoryNormalisingTheSlug() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/categories").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"  Nightlife \",\"label\":\" Nightlife \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("nightlife"))
                .andExpect(jsonPath("$.label").value("Nightlife"));
    }

    @Test
    void duplicateSlugIsConflict() throws Exception {
        createCategory("dupcat", "Dup");
        mockMvc.perform(post("/supportgroup/v1/internal/categories").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"dupcat\",\"label\":\"Dup2\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void blankSlugIsRejected() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/categories").header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slug\":\"\",\"label\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReportsAffectedGroupsAndCascadesOffTheGroup() throws Exception {
        createConfig("cat-del-grp");
        createCategory("todelete", "ToDelete");
        mockMvc.perform(put("/supportgroup/v1/internal/groups/{ig}/categories/{slug}", "cat-del-grp", "todelete")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/supportgroup/v1/internal/categories/{slug}", "todelete").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedGroups").value(1));

        mockMvc.perform(get("/supportgroup/v1/internal/groups/{ig}", "cat-del-grp").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(0));
    }

    @Test
    void deleteUnusedCategoryReportsZeroAffected() throws Exception {
        createCategory("unusedcat", "Unused");
        mockMvc.perform(delete("/supportgroup/v1/internal/categories/{slug}", "unusedcat").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedGroups").value(0));
    }

    @Test
    void deleteUnknownCategoryIsNotFound() throws Exception {
        mockMvc.perform(delete("/supportgroup/v1/internal/categories/{slug}", "no-such-cat").header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }
}
