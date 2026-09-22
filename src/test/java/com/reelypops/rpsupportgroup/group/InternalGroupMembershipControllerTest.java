package com.reelypops.rpsupportgroup.group;

import com.reelypops.rpsupportgroup.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The registry read GROUP-first ({@code /supportgroup/v1/internal/groups/{igAccount}/memberships}) — the inverse
 * of the user-first surface, and the first half of answering "which machines could deep-scrape this group".
 *
 * <p>The property that matters most here is the last one: a RELEASED membership must not come back. A scrape
 * duty offered against a group somebody has given up would be a button that does nothing, because the client
 * that receives it self-filters on whether it still runs the group.</p>
 */
@SpringBootTest(properties = "rp.internal.api-key=itest-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalGroupMembershipControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "itest-key";
    private static final String USER_PATH = "/supportgroup/v1/internal/users/{userId}/memberships";
    private static final String GROUP_PATH = "/supportgroup/v1/internal/groups/{igAccount}/memberships";

    @Autowired
    MockMvc mockMvc;

    private void claim(UUID user, String handle, String account) throws Exception {
        mockMvc.perform(post(USER_PATH + "/claim", user).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"igHandle\":\"" + handle + "\",\"igAccount\":\"" + account + "\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void readWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get(GROUP_PATH, "grp.auth"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnheldGroupIsAnEmptyArray_notAnError() throws Exception {
        // An operator asking about a group nobody runs must be able to tell "nobody" from "the call failed".
        mockMvc.perform(get(GROUP_PATH, "grp.nobody").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void namesEveryHolderOfTheGroup_withTheUserAndTheHandle() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        claim(alice, "alice.one", "grp.shared");
        claim(bob, "bob.one", "grp.shared");
        claim(alice, "alice.one", "grp.other");   // a different group must not leak in

        mockMvc.perform(get(GROUP_PATH, "grp.shared").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.userId=='" + alice + "')].igHandle").value("alice.one"))
                .andExpect(jsonPath("$[?(@.userId=='" + bob + "')].igHandle").value("bob.one"))
                .andExpect(jsonPath("$[0].igAccount").value("grp.shared"));
    }

    @Test
    void oneCustomerHoldingOneGroupOnTwoHandlesIsTwoRows() throws Exception {
        // Two handles, one customer: the duty is per MACHINE, and a machine is reached through a handle, so
        // collapsing these to one row would lose the only thing that distinguishes them.
        UUID user = UUID.randomUUID();
        claim(user, "handle.a", "grp.twohandles");
        claim(user, "handle.b", "grp.twohandles");

        mockMvc.perform(get(GROUP_PATH, "grp.twohandles").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void aReleasedMembershipIsGone_soNoDutyIsOfferedAgainstIt() throws Exception {
        UUID user = UUID.randomUUID();
        claim(user, "leaver.one", "grp.left");
        mockMvc.perform(get(GROUP_PATH, "grp.left").header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(delete(USER_PATH, user).header(KEY_HEADER, KEY)
                        .param("igHandle", "leaver.one").param("igAccount", "grp.left"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(GROUP_PATH, "grp.left").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theAccountIsMatchedCaseInsensitively() throws Exception {
        // Handles are stored lower-cased; an operator typing the group with capitals must still find it.
        claim(UUID.randomUUID(), "case.one", "grp.case");

        mockMvc.perform(get(GROUP_PATH, "GRP.CASE").header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
