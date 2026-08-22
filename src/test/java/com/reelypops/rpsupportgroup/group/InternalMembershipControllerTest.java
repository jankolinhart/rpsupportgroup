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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The B6 internal membership endpoints ({@code /supportgroup/v1/internal/users/{userId}/memberships}) behind the
 * shared {@code X-Internal-Api-Key}: the idempotent batch WRITE, the read-back, the absence rule, and the auth
 * boundary.
 */
@SpringBootTest(properties = "rp.internal.api-key=itest-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalMembershipControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "itest-key";
    private static final String PATH = "/supportgroup/v1/internal/users/{userId}/memberships";

    @Autowired
    MockMvc mockMvc;

    private void write(UUID user, String body) throws Exception {
        mockMvc.perform(post(PATH, user).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    void writeWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post(PATH, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get(PATH, UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void writeUpsertsTheBatchAndReadReturnsTheRows() throws Exception {
        UUID user = UUID.randomUUID();
        write(user, "[{\"igHandle\":\"Owner\",\"igAccount\":\"AcctA\",\"followingStatus\":\"following\"},"
                + "{\"igHandle\":\"Owner\",\"igAccount\":\"AcctB\",\"followingStatus\":\"not_following\"}]");

        mockMvc.perform(get(PATH, user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.igAccount=='accta')].status").value("FOLLOWING"))
                .andExpect(jsonPath("$[?(@.igAccount=='accta')].followingConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$[?(@.igAccount=='accta')].instantiatedAt").isNotEmpty())
                .andExpect(jsonPath("$[?(@.igAccount=='acctb')].status").value("NOT_FOLLOWING"));
    }

    @Test
    void readForAnUnknownUserIsAnEmptyArray() throws Exception {
        mockMvc.perform(get(PATH, UUID.randomUUID()).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void absenceNeverChangesAnUnreportedRow() throws Exception {
        UUID user = UUID.randomUUID();
        write(user, "[{\"igHandle\":\"Owner\",\"igAccount\":\"Keep\",\"followingStatus\":\"following\"}]");
        // A later batch that simply omits "Keep" must not delete or alter it (absence != unfollow).
        write(user, "[{\"igHandle\":\"Owner\",\"igAccount\":\"Other\",\"followingStatus\":\"following\"}]");

        mockMvc.perform(get(PATH, user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.igAccount=='keep')].status").value("FOLLOWING"));
    }

    @Test
    void anInconclusiveElementIsAppliedAsANoOp() throws Exception {
        UUID user = UUID.randomUUID();
        write(user, "[{\"igHandle\":\"Owner\",\"igAccount\":\"AcctA\",\"followingStatus\":\"following\"}]");
        // "unknown" for the same account must preserve the stored FOLLOWING and never insert a duplicate.
        write(user, "[{\"igHandle\":\"Owner\",\"igAccount\":\"AcctA\",\"followingStatus\":\"unknown\"}]");

        mockMvc.perform(get(PATH, user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("FOLLOWING"));
    }

    @Test
    void aBlankHandleIsRejected() throws Exception {
        mockMvc.perform(post(PATH, UUID.randomUUID()).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"igHandle\":\"\",\"igAccount\":\"AcctA\",\"followingStatus\":\"following\"}]"))
                .andExpect(status().isBadRequest());
    }
}
