package com.reelypops.rpsupportgroup.vetting;

import com.reelypops.rpsupportgroup.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal advisory-vetting read surface ({@code /supportgroup/v1/internal/vetting}). Covers the shared-key guard, the
 * 404 for an unknown snapshot, and the two Tier-0 outcomes end-to-end: a recurring banner proposes FLAT_BANNER with the
 * owner roster; a grid where nothing recurs escalates as TEXT_OVERLAY. Exercises the real (no-op) enricher wiring.
 */
@SpringBootTest(properties = {"rp.internal.api-key=itest-key"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalVettingControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "itest-key";
    private static final String MARKER = "1".repeat(64);
    private static final String OTHER = "0".repeat(64);                  // Hamming 64 from MARKER
    private static final String HALF = "0".repeat(32) + "1".repeat(32);  // Hamming 32 from each

    @Autowired
    MockMvc mockMvc;

    private void createConfig(String ig) throws Exception {
        String body = "{\"igAccount\":\"" + ig + "\",\"definition\":{\"type\":\"CONTINUOUS\",\"timezone\":\"UTC\"}}";
        mockMvc.perform(post("/supportgroup/v1/groups")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private String openSnapshot(String ig) throws Exception {
        createConfig(ig);
        MvcResult r = mockMvc.perform(post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", ig)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"REQUEST\",\"capturedByAccount\":\"scraper-acct\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    private void append(String id, String shortcode, String author, String dHash, int ordinal) throws Exception {
        String body = "{\"items\":[{\"shortcode\":\"" + shortcode + "\",\"authorUsername\":\"" + author
                + "\",\"dHash\":\"" + dHash + "\",\"ordinal\":" + ordinal + "}]}";
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private void seal(String id) throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk());
    }

    @Test
    void withoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/vetting/snapshots/{id}/proposal", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownSnapshotIs404() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/vetting/snapshots/{id}/proposal", UUID.randomUUID())
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void proposesFlatBannerFromRecurringMarker() throws Exception {
        String id = openSnapshot("vet-flat");
        append(id, "sc1", "owner.acct", MARKER, 0);
        append(id, "sc2", "owner.acct", MARKER, 1);
        append(id, "sc3", "owner.acct", MARKER, 2);
        append(id, "sc4", "member.a", OTHER, 3);
        seal(id);

        mockMvc.perform(get("/supportgroup/v1/internal/vetting/snapshots/{id}/proposal", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedType").value("FLAT_BANNER"))
                .andExpect(jsonPath("$.itemCount").value(4))
                .andExpect(jsonPath("$.ownerRoster[0]").value("owner.acct"))
                .andExpect(jsonPath("$.escalate").value(false))
                .andExpect(jsonPath("$.markerClusters[0].size").value(3))
                .andExpect(jsonPath("$.markerClusters[0].authorUsernames[0]").value("owner.acct"))
                .andExpect(jsonPath("$.provenance").value("TIER_0_DHASH"));
    }

    @Test
    void proposesTextOverlayWhenNothingRecurs() throws Exception {
        String id = openSnapshot("vet-text");
        append(id, "sc1", "a.acct", MARKER, 0);
        append(id, "sc2", "b.acct", OTHER, 1);
        append(id, "sc3", "c.acct", HALF, 2);
        seal(id);

        mockMvc.perform(get("/supportgroup/v1/internal/vetting/snapshots/{id}/proposal", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedType").value("TEXT_OVERLAY"))
                .andExpect(jsonPath("$.escalate").value(true))
                .andExpect(jsonPath("$.confidence").value(0.0));
    }
}
