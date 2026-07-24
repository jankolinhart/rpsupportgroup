package com.reelypops.rpsupportgroup.corpus;

import com.reelypops.rpsupportgroup.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal corpus intake + read surface ({@code /supportgroup/v1/internal/corpus}), authenticated by the shared
 * {@code X-Internal-Api-Key}. Covers the append-only snapshot lifecycle (open → per-scroll append → seal) and the
 * admin evidence reads (list + detail), plus the guards (unknown group/snapshot, append-after-seal, validation).
 */
@SpringBootTest(properties = "rp.internal.api-key=itest-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalCorpusControllerTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
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

    /** Create a group then open a snapshot for it, returning the snapshot id. */
    private String openSnapshot(String ig, String source) throws Exception {
        createConfig(ig);
        MvcResult r = mockMvc.perform(post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", ig)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"" + source + "\",\"capturedByAccount\":\"scraper-acct\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.itemCount").value(0))
                .andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    private String appendBody(String shortcode, String author, String dHash, int ordinal) {
        return "{\"items\":[{\"shortcode\":\"" + shortcode + "\",\"authorUsername\":\"" + author
                + "\",\"dHash\":\"" + dHash + "\",\"postedAt\":\"2026-07-20T10:15:30Z\",\"ordinal\":" + ordinal + "}]}";
    }

    @Test
    void withoutKeyIsUnauthorized() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void opensAnEmptySnapshot() throws Exception {
        String id = openSnapshot("corp-open", "REQUEST");
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.source").value("REQUEST"))
                .andExpect(jsonPath("$.snapshot.capturedByAccount").value("scraper-acct"))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void openForUnknownGroupIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", "corp-ghost")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"REQUEST\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void openWithoutSourceIsBadRequest() throws Exception {
        createConfig("corp-nosource");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", "corp-nosource")
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void appendsItemsPerScroll() throws Exception {
        String id = openSnapshot("corp-append", "DUTY");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "owner1", "hash-a", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(1));
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("BBB", "member1", "hash-b", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2));
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].shortcode").value("AAA"))
                .andExpect(jsonPath("$.items[0].authorUsername").value("owner1"))
                .andExpect(jsonPath("$.items[0].postedAt").value("2026-07-20T10:15:30Z"))
                .andExpect(jsonPath("$.items[1].shortcode").value("BBB"));
    }

    @Test
    void appendToUnknownSnapshotIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", UUID.randomUUID())
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("X", "a", "h", 0)))
                .andExpect(status().isNotFound());
    }

    @Test
    void appendEmptyBatchIsBadRequest() throws Exception {
        String id = openSnapshot("corp-empty", "REQUEST");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void appendInvalidItemIsBadRequest() throws Exception {
        String id = openSnapshot("corp-badit", "SCANNER");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"shortcode\":\"S\",\"authorUsername\":\"a\",\"dHash\":\"\",\"ordinal\":0}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sealsASnapshotAndBlocksFurtherAppends() throws Exception {
        String id = openSnapshot("corp-seal", "ADMIN");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "owner1", "hash-a", 0)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.sealedAt").exists());
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("BBB", "member1", "hash-b", 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void sealIsIdempotent() throws Exception {
        String id = openSnapshot("corp-seal-idem", "ADMIN");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
    }

    @Test
    void sealUnknownSnapshotIsNotFound() throws Exception {
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/seal", UUID.randomUUID())
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsAGroupsSnapshots() throws Exception {
        String ig = "corp-list";
        openSnapshot(ig, "REQUEST");
        // a second snapshot for the same group (the config already exists)
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", ig)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"DUTY\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/groups/{ig}/snapshots", ig).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].source", Matchers.containsInAnyOrder("REQUEST", "DUTY")));
    }

    @Test
    void detailForUnknownSnapshotIsNotFound() throws Exception {
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", UUID.randomUUID())
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void putsAndServesARepresentative() throws Exception {
        String id = openSnapshot("corp-rep", "REQUEST");
        mockMvc.perform(post("/supportgroup/v1/internal/corpus/snapshots/{id}/items", id)
                        .header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(appendBody("AAA", "owner1", "hash-a", 0)))
                .andExpect(status().isOk());
        byte[] png = {1, 2, 3, 4, 5};
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_PNG).content(png))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png));
        // the detail view reports which shortcodes carry a representative
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}", id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.representativeShortcodes.length()").value(1))
                .andExpect(jsonPath("$.representativeShortcodes[0]").value("AAA"));
    }

    @Test
    void representativeUploadUpsertsBytes() throws Exception {
        String id = openSnapshot("corp-rep-upsert", "DUTY");
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_JPEG).content(new byte[]{1, 1}))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_PNG).content(new byte[]{9, 9, 9}))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{9, 9, 9}));
    }

    @Test
    void missingRepresentativeIsNotFound() throws Exception {
        String id = openSnapshot("corp-rep-none", "REQUEST");
        mockMvc.perform(get("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "ZZZ")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void putRepresentativeForUnknownSnapshotIsNotFound() throws Exception {
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}",
                        UUID.randomUUID(), "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_PNG).content(new byte[]{1}))
                .andExpect(status().isNotFound());
    }

    @Test
    void putEmptyRepresentativeIsBadRequest() throws Exception {
        String id = openSnapshot("corp-rep-empty", "REQUEST");
        mockMvc.perform(put("/supportgroup/v1/internal/corpus/snapshots/{id}/representatives/{sc}", id, "AAA")
                        .header(KEY_HEADER, KEY).contentType(MediaType.IMAGE_PNG).content(new byte[0]))
                .andExpect(status().isBadRequest());
    }
}
