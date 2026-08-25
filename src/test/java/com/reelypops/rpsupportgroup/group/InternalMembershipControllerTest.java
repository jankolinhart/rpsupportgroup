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
 * The B6 internal membership endpoints ({@code /supportgroup/v1/internal/users/{userId}/memberships}) behind the
 * shared {@code X-Internal-Api-Key}: the idempotent batch WRITE, the read-back, the CLAIM, the RELEASE, the
 * absence rule, and the auth boundary.
 *
 * <p>The claim and the report are separate routes here for a reason the last section proves end to end: only the
 * claim may undo a release.</p>
 */
@SpringBootTest(properties = "rp.internal.api-key=itest-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalMembershipControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String KEY = "itest-key";
    private static final String PATH = "/supportgroup/v1/internal/users/{userId}/memberships";
    private static final String CLAIM_PATH = PATH + "/claim";

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

    // ── the RELEASE: the DELETE half of the claim ───────────────────────────────────────────────────

    private void release(UUID user, String handle, String account) throws Exception {
        mockMvc.perform(delete(PATH, user).header(KEY_HEADER, KEY)
                        .param("igHandle", handle).param("igAccount", account))
                .andExpect(status().isNoContent());
    }

    @Test
    void releaseWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(delete(PATH, UUID.randomUUID()).param("igHandle", "h").param("igAccount", "g"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The number this whole feature exists for: {@code MembershipQuotaService} counts the FOLLOWING rows of this
     * very read to enforce {@code groups.max}, so a release has to make that count fall by EXACTLY one — and take
     * the right row with it.
     */
    @Test
    void releaseRemovesOneRowAndTheCountFallsByExactlyOne() throws Exception {
        UUID user = UUID.randomUUID();
        write(user, "[{\"igHandle\":\"Owner\",\"igAccount\":\"Gone\",\"followingStatus\":\"following\"},"
                + "{\"igHandle\":\"Owner\",\"igAccount\":\"Keep\",\"followingStatus\":\"following\"},"
                + "{\"igHandle\":\"Second\",\"igAccount\":\"Gone\",\"followingStatus\":\"following\"}]");

        release(user, "Owner", "Gone");

        mockMvc.perform(get(PATH, user).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.igHandle=='owner' && @.igAccount=='gone')]").isEmpty())
                // the same group under the user's OTHER handle survives — the release is keyed on the triple
                .andExpect(jsonPath("$[?(@.igHandle=='second' && @.igAccount=='gone')].status").value("FOLLOWING"))
                .andExpect(jsonPath("$[?(@.igHandle=='owner' && @.igAccount=='keep')].status").value("FOLLOWING"));
    }

    /** Idempotent by contract: the client retries this, and a membership already gone is the asked-for state. */
    @Test
    void releasingTwiceIsASuccess() throws Exception {
        UUID user = UUID.randomUUID();
        write(user, "[{\"igHandle\":\"Owner\",\"igAccount\":\"AcctA\",\"followingStatus\":\"following\"}]");

        release(user, "Owner", "AcctA");
        release(user, "Owner", "AcctA");   // 204 again, not 404

        mockMvc.perform(get(PATH, user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void releasingAMembershipNeverHeldIsStillASuccess() throws Exception {
        release(UUID.randomUUID(), "Owner", "NeverJoined");
    }

    @Test
    void releaseWithoutTheHandleOrTheGroupIsRejected() throws Exception {
        UUID user = UUID.randomUUID();
        mockMvc.perform(delete(PATH, user).header(KEY_HEADER, KEY).param("igAccount", "g"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete(PATH, user).header(KEY_HEADER, KEY).param("igHandle", "h"))
                .andExpect(status().isBadRequest());
        // Blank is refused rather than normalised away: a caller must never delete by accident.
        mockMvc.perform(delete(PATH, user).header(KEY_HEADER, KEY)
                        .param("igHandle", " ").param("igAccount", "g"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBlankHandleIsRejected() throws Exception {
        mockMvc.perform(post(PATH, UUID.randomUUID()).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"igHandle\":\"\",\"igAccount\":\"AcctA\",\"followingStatus\":\"following\"}]"))
                .andExpect(status().isBadRequest());
    }

    // ── the CLAIM: the explicit join, and the only thing that undoes a release ───────────────────────

    private void claim(UUID user, String handle, String account) throws Exception {
        mockMvc.perform(post(CLAIM_PATH, user).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"igHandle\":\"" + handle + "\",\"igAccount\":\"" + account + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void claimWithoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post(CLAIM_PATH, UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"igHandle\":\"h\",\"igAccount\":\"g\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aClaimNeedsBothHalvesOfTheNaturalKey() throws Exception {
        mockMvc.perform(post(CLAIM_PATH, UUID.randomUUID()).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"igHandle\":\" \",\"igAccount\":\"g\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(CLAIM_PATH, UUID.randomUUID()).header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"igAccount\":\"g\"}"))
                .andExpect(status().isBadRequest());
    }

    /** An ordinary first join over the claim route behaves exactly like a positive report: one FOLLOWING row. */
    @Test
    void aClaimRecordsTheMembershipAsFollowing() throws Exception {
        UUID user = UUID.randomUUID();

        claim(user, "Owner", "AcctA");

        mockMvc.perform(get(PATH, user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].igHandle").value("owner"))
                .andExpect(jsonPath("$[0].status").value("FOLLOWING"));
    }

    /**
     * <strong>The ruling, end to end over the wire: an explicit act beats an observation.</strong>
     *
     * <p>The device report is the SAME upsert the claim uses, arriving on the batch route — and it is exactly
     * what used to re-take the slot the user had just been given back. It no longer does; the claim, which is the
     * user acting rather than a client observing, still does.</p>
     */
    @Test
    void aReportCannotUndoAReleaseAndAClaimCan() throws Exception {
        UUID user = UUID.randomUUID();
        write(user, "[{\"igHandle\":\"Owner\",\"igAccount\":\"AcctA\",\"followingStatus\":\"following\"}]");
        release(user, "Owner", "AcctA");

        // The desktop's next device report, forwarded verbatim by rpenduser.
        write(user, "[{\"igHandle\":\"Owner\",\"igAccount\":\"AcctA\",\"followingStatus\":\"following\"}]");
        mockMvc.perform(get(PATH, user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.length()").value(0));

        // The user joins the group again — which must work exactly as it always did.
        claim(user, "Owner", "AcctA");
        mockMvc.perform(get(PATH, user).header(KEY_HEADER, KEY))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("FOLLOWING"));
    }
}
