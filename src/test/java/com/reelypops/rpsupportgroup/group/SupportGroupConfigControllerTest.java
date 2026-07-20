package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.TestcontainersConfiguration;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @Autowired
    SupportGroupConfigService service;

    private static RequestPostProcessor asUser() {
        return asUser(UUID.randomUUID());
    }

    private static RequestPostProcessor asUser(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()));
    }

    private static RequestPostProcessor asAdmin(UUID id) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_SG_ADMIN")).jwt(j -> j.subject(id.toString()));
    }

    private static String markerOwner(String handle) {
        return "{\"handle\":\"" + handle + "\"}";
    }

    private static String minimalBody(String ig) {
        return "{\"igAccount\":\"" + ig + "\",\"definition\":{\"type\":\"CONTINUOUS\",\"timezone\":\"UTC\"}}";
    }

    private static String body(String ig) {
        return "{"
                + "\"igAccount\":\"" + ig + "\","
                + "\"definition\":{"
                + "\"type\":\"SINGLE_MARKER\","
                + "\"timezone\":\"Europe/Berlin\","
                + "\"markerOwners\":[\"owner1\"],"
                + "\"maxTaggedPosts\":3,"
                + "\"continuousDays\":[0,1,2,3,4,5,6],"
                + "\"startMarkerTime\":\"09:00\","
                + "\"endMarkerTime\":\"18:00\","
                + "\"endMarkerDayOffset\":0,"
                + "\"singleMarkerTime\":\"12:00\","
                + "\"likesUntilTime\":\"04:00\","
                + "\"likesUntilDayOffset\":1,"
                + "\"tagRemoveEarliestTime\":\"08:00\","
                + "\"tagRemoveEarliestDayOffset\":1}"
                + "}";
    }

    @Test
    void createRegistersUnclaimedConfig() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content(body("sg-alpha")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.igAccount").value("sg-alpha"))
                .andExpect(jsonPath("$.status").value("UNCLAIMED"))
                .andExpect(jsonPath("$.vetted").value(false))
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
    void listReturnsOnlyVettedConfigs() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-list-pending"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-list-vetted"))).andExpect(status().isCreated());
        service.vet("sg-list-vetted");

        mockMvc.perform(get("/supportgroup/v1/groups").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].igAccount", Matchers.hasItem("sg-list-vetted")))
                .andExpect(jsonPath("$[*].igAccount", Matchers.not(Matchers.hasItem("sg-list-pending"))));
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

    // --- claim / promote (§6) ---

    @Test
    void ownerClaimsUnclaimedConfig() throws Exception {
        UUID owner = UUID.randomUUID();
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-claim"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/claim", "sg-claim").with(asAdmin(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.ownerId").value(owner.toString()))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void claimAlreadyClaimedIsConflict() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-claim2"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/claim", "sg-claim2").with(asAdmin(UUID.randomUUID())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/claim", "sg-claim2").with(asAdmin(UUID.randomUUID())))
                .andExpect(status().isConflict());
    }

    @Test
    void nonAdminCannotClaim() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-claim3"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/claim", "sg-claim3").with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void claimUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/claim", "sg-claim-none").with(asAdmin(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    // --- marker-owner discovery write (Q4) ---

    @Test
    void clientAddsDiscoveredMarkerOwner() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-mo"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/marker-owners", "sg-mo").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content(markerOwner("owner2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.markerOwners.length()").value(2))
                .andExpect(jsonPath("$.definition.markerOwners", Matchers.hasItem("owner2")))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void addMarkerOwnerIsIdempotent() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-moi"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/marker-owners", "sg-moi").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(markerOwner("owner2"))).andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/marker-owners", "sg-moi").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content(markerOwner("owner2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.markerOwners.length()").value(2))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void addMarkerOwnerOnConfigWithoutOwners() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(minimalBody("sg-mow"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/marker-owners", "sg-mow").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content(markerOwner("owner1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.markerOwners.length()").value(1))
                .andExpect(jsonPath("$.definition.markerOwners", Matchers.hasItem("owner1")));
    }

    @Test
    void addMarkerOwnerUnknownConfigIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/marker-owners", "sg-mo-none").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content(markerOwner("owner2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void addMarkerOwnerBlankIsRejected() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-mob"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/marker-owners", "sg-mob").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON).content(markerOwner("")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownerRevokesMarkerOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-rev"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/claim", "sg-rev").with(asAdmin(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/marker-owners", "sg-rev").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(markerOwner("owner2"))).andExpect(status().isOk());
        mockMvc.perform(delete("/supportgroup/v1/groups/{ig}/marker-owners/{h}", "sg-rev", "owner2").with(asAdmin(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.markerOwners.length()").value(1))
                .andExpect(jsonPath("$.definition.markerOwners", Matchers.not(Matchers.hasItem("owner2"))));
    }

    @Test
    void revokeMissingMarkerOwnerIsNoop() throws Exception {
        UUID owner = UUID.randomUUID();
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-revn"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/claim", "sg-revn").with(asAdmin(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/supportgroup/v1/groups/{ig}/marker-owners/{h}", "sg-revn", "ghost").with(asAdmin(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.markerOwners.length()").value(1));
    }

    @Test
    void nonOwnerAdminCannotRevoke() throws Exception {
        UUID owner = UUID.randomUUID();
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-revo"))).andExpect(status().isCreated());
        mockMvc.perform(post("/supportgroup/v1/groups/{ig}/claim", "sg-revo").with(asAdmin(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/supportgroup/v1/groups/{ig}/marker-owners/{h}", "sg-revo", "owner1")
                        .with(asAdmin(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonAdminCannotRevoke() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-revu"))).andExpect(status().isCreated());
        mockMvc.perform(delete("/supportgroup/v1/groups/{ig}/marker-owners/{h}", "sg-revu", "owner1").with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokeOnUnclaimedConfigIsForbidden() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/groups").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body("sg-revx"))).andExpect(status().isCreated());
        mockMvc.perform(delete("/supportgroup/v1/groups/{ig}/marker-owners/{h}", "sg-revx", "owner1")
                        .with(asAdmin(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }
}
