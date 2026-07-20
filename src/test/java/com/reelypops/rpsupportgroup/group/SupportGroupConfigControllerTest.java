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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack SG-config test: real controller + service + repository against a Testcontainers Postgres
 * (Liquibase-migrated, jsonb definition). Auth is a mock JWT (any authenticated ReelyPops client may register
 * an unclaimed config). Each test uses a distinct ig_account so cases stay isolated without cleanup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SupportGroupConfigControllerTest {

    @Autowired
    MockMvc mockMvc;

    private static RequestPostProcessor asUser() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()));
    }

    private static String body(String ig) {
        return "{"
                + "\"igAccount\":\"" + ig + "\","
                + "\"definition\":{"
                + "\"type\":\"SINGLE_MARKER\","
                + "\"timezone\":\"Europe/Berlin\","
                + "\"markerOwners\":[\"owner1\"],"
                + "\"openingDays\":[\"MON\",\"TUE\"],"
                + "\"maxTags\":3,"
                + "\"safeTagRemovalMinutes\":30,"
                + "\"likingEndMinutes\":120}"
                + "}";
    }

    @Test
    void createRegistersUnclaimedConfig() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content(body("sg-alpha")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.igAccount").value("sg-alpha"))
                .andExpect(jsonPath("$.status").value("UNCLAIMED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.definition.type").value("SINGLE_MARKER"))
                .andExpect(jsonPath("$.definition.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void duplicateIgAccountIsConflict() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-dup"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-dup"))).andExpect(status().isConflict());
    }

    @Test
    void getReturnsTheConfig() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-get"))).andExpect(status().isCreated());
        mockMvc.perform(get("/supportgroup/v1/groups/{ig}", "sg-get").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.igAccount").value("sg-get"))
                .andExpect(jsonPath("$.definition.markerOwners[0]").value("owner1"));
    }

    @Test
    void getUnknownIsNotFound() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/groups/{ig}", "sg-missing").with(asUser()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsConfigs() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-list-1"))).andExpect(status().isCreated());
        mockMvc.perform(get("/supportgroup/v1/groups").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups")
                        .contentType(MediaType.APPLICATION_JSON).content(body("sg-x")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankIgAccountIsRejected() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isBadRequest());
    }
}
