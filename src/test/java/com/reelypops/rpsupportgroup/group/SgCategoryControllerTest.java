package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.TestcontainersConfiguration;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public category taxonomy ({@code /supportgroup/v1/categories}) — the master list backing the client's
 * "choose a support group" filter chips. Any authenticated ReelyPops client may read it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SgCategoryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void listsTheSeededTaxonomyToAuthenticatedClients() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/categories")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug", Matchers.hasItems(
                        "travel", "dark", "photography", "social", "dining", "motors", "sensual", "food",
                        "guide", "international", "glamour", "club", "politics", "lifestyle", "fashion")))
                .andExpect(jsonPath("$[?(@.slug=='travel')].label", Matchers.hasItem("Travel")));
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/categories"))
                .andExpect(status().isUnauthorized());
    }
}
