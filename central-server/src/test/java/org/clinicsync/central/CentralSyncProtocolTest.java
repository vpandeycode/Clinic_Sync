package org.clinicsync.central;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.clinicsync.common.model.ChangeRecord;
import org.clinicsync.common.model.SyncBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 sync protocol acceptance: enrolment, integrity guards, gap detection, HLC drift
 * rejection, pull paging with scope filtering, and concurrent-branch detection.
 *
 * <p>All tests share the module's H2 database, so every test uses its own node ids and resource
 * ids to stay isolated.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CentralSyncProtocolTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    private void register(String nodeId, String scope) throws Exception {
        String body = "{\"nodeId\":\"" + nodeId + "\",\"publicKey\":\"cHVibGljLWtleS0-" + nodeId
                + "\",\"clinicScope\":\"" + scope + "\"}";
        mockMvc.perform(post("/api/v1/nodes/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private static ChangeRecord change(long seq, String type, String id, String versionId, String parents,
                                       String nodeId, String hlc, String json) {
        return new ChangeRecord(seq, type, id, versionId, json, parents, nodeId, hlc, "clinician", false);
    }

    private String push(String nodeId, String scope, ChangeRecord... changes) throws Exception {
        SyncBatch batch = new SyncBatch("batch-" + nodeId + "-" + changes[0].originSeq(), nodeId, scope, List.of(changes));
        MvcResult result = mockMvc.perform(post("/api/v1/sync/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private static String patientJson(String id, String family) {
        return """
                {"resourceType":"Patient","id":"%s","name":[{"family":"%s","given":["Syn"]}],"gender":"other"}
                """.formatted(id, family);
    }

    // ------------------------------------------------------------------
    // Enrolment and integrity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Push from an unregistered node is rejected with NODE_NOT_REGISTERED")
    void pushFromUnregisteredNodeIsRejected() throws Exception {
        String body = push("ghost-node", "clinic-x", change(1, "Patient", "p-ghost", "1-ghost-node", null,
                "ghost-node", "1700000000000:0:ghost-node", patientJson("p-ghost", "Ghost")));

        assertThat(body).contains("NODE_NOT_REGISTERED");
    }

    @Test
    @DisplayName("A revoked node is rejected on push, pull and re-registration")
    void revokedNodeIsRejectedEverywhere() throws Exception {
        register("revocable-node", "clinic-rev");
        mockMvc.perform(post("/api/v1/nodes/revocable-node/revoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        String pushBody = push("revocable-node", "clinic-rev", change(1, "Patient", "p-rev", "1-revocable-node", null,
                "revocable-node", "1700000000000:0:revocable-node", patientJson("p-rev", "Rev")));
        assertThat(pushBody).contains("NODE_REVOKED");

        mockMvc.perform(get("/api/v1/sync/pull").param("nodeId", "revocable-node"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/nodes/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"revocable-node\",\"publicKey\":\"bmV3\",\"clinicScope\":\"clinic-rev\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A batch declaring a clinic scope different from the registered one is rejected")
    void scopeMismatchIsRejected() throws Exception {
        register("scope-node", "clinic-scope-a");

        String body = push("scope-node", "clinic-scope-b", change(1, "Patient", "p-scope", "1-scope-node", null,
                "scope-node", "1700000000000:0:scope-node", patientJson("p-scope", "Scope")));
        assertThat(body).contains("SCOPE_MISMATCH");
    }

    @Test
    @DisplayName("Invalid FHIR payload inside a batch is rejected with 400")
    void invalidFhirInBatchIsRejected() throws Exception {
        register("fhir-node", "clinic-fhir");

        ChangeRecord bad = change(1, "Patient", "p-bad", "1-fhir-node", null, "fhir-node",
                "1700000000000:0:fhir-node", "{\"resourceType\":\"Patient\",\"name\":\"not-an-array\"}");

        mockMvc.perform(post("/api/v1/sync/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SyncBatch("batch-bad", "fhir-node", "clinic-fhir", List.of(bad)))))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Ordering, gaps and idempotency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A missing origin_seq stops the batch at the contiguous prefix and resumes after replay")
    void gapDetectionAndReplay() throws Exception {
        register("gap-node", "clinic-gap");

        String first = push("gap-node", "clinic-gap", change(1, "Patient", "p-gap-1", "1-gap-node", null,
                "gap-node", "1700000000000:0:gap-node", patientJson("p-gap-1", "GapOne")));
        assertThat(first).contains("\"highestContiguousOriginSeq\":1");
        assertThat(first).contains("\"status\":\"APPLIED\"");

        // Seq 2 is lost in transit: central must accept nothing beyond the gap.
        String second = push("gap-node", "clinic-gap", change(3, "Patient", "p-gap-3", "1-gap-node", null,
                "gap-node", "1700000000000:0:gap-node", patientJson("p-gap-3", "GapThree")));
        assertThat(second).contains("\"highestContiguousOriginSeq\":1");
        assertThat(second).contains("\"status\":\"PARTIAL\"");

        // Replay with the missing change plus the one that was held back.
        String third = push("gap-node", "clinic-gap",
                change(2, "Patient", "p-gap-2", "1-gap-node", null, "gap-node",
                        "1700000000000:0:gap-node", patientJson("p-gap-2", "GapTwo")),
                change(3, "Patient", "p-gap-3", "1-gap-node", null, "gap-node",
                        "1700000000000:0:gap-node", patientJson("p-gap-3", "GapThree")));
        assertThat(third).contains("\"highestContiguousOriginSeq\":3");
        assertThat(third).contains("\"appliedCount\":2");
    }

    @Test
    @DisplayName("Replaying an already applied change is idempotent (0 applied) and must not alter content")
    void replayedBatchIsIdempotent() throws Exception {
        register("idem-node", "clinic-idem");

        ChangeRecord change = change(1, "Patient", "p-idem", "1-idem-node", null, "idem-node",
                "1700000000000:0:idem-node", patientJson("p-idem", "Idem"));

        String first = push("idem-node", "clinic-idem", change);
        assertThat(first).contains("\"appliedCount\":1");

        String second = push("idem-node", "clinic-idem", change);
        assertThat(second).contains("\"appliedCount\":0");
        assertThat(second).contains("\"highestContiguousOriginSeq\":1");
    }

    @Test
    @DisplayName("A change whose HLC lies beyond the future-drift bound is rejected with 422")
    void futureDriftIsRejected() throws Exception {
        register("drift-node", "clinic-drift");

        ChangeRecord drifted = change(1, "Patient", "p-drift", "1-drift-node", null, "drift-node",
                "99999999999999:0:drift-node", patientJson("p-drift", "Drift"));

        mockMvc.perform(post("/api/v1/sync/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SyncBatch("batch-drift", "drift-node", "clinic-drift", List.of(drifted)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issue[0].code").value("HLC_DRIFT"));
    }

    // ------------------------------------------------------------------
    // Pull: cursor, paging, scope filtering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Pull returns changes in global_seq order with bounded pages and a moving cursor")
    void pullPagesWithCursor() throws Exception {
        register("pull-node", "clinic-pull");

        List<ChangeRecord> changes = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            changes.add(change(i, "Patient", "p-pull-" + i, "1-pull-node", null, "pull-node",
                    "170000000000" + i + ":0:pull-node", patientJson("p-pull-" + i, "Pull" + i)));
        }
        String ack = push("pull-node", "clinic-pull", changes.toArray(ChangeRecord[]::new));
        assertThat(ack).contains("\"highestContiguousOriginSeq\":5");

        // Page through the whole feed with limit=2 and verify our five changes arrive exactly
        // once, in strictly increasing global_seq order, with a cursor that never moves back.
        List<String> receivedIds = new ArrayList<>();
        List<Long> receivedGlobalSeqs = new ArrayList<>();
        long cursor = 0;
        boolean hasMore = true;
        int pages = 0;

        while (hasMore && pages < 100) {
            MvcResult page = mockMvc.perform(get("/api/v1/sync/pull")
                            .param("nodeId", "pull-node")
                            .param("since", String.valueOf(cursor))
                            .param("limit", "2"))
                    .andExpect(status().isOk())
                    .andReturn();

            var tree = objectMapper.readTree(page.getResponse().getContentAsString());
            long nextGlobalSeq = tree.get("nextGlobalSeq").asLong();
            assertThat(nextGlobalSeq).isGreaterThanOrEqualTo(cursor);

            for (var node : tree.get("changes")) {
                if ("pull-node".equals(node.get("originNodeId").asText())) {
                    receivedIds.add(node.get("resourceId").asText());
                    receivedGlobalSeqs.add(node.get("globalSeq").asLong());
                }
            }

            cursor = nextGlobalSeq;
            hasMore = tree.get("hasMore").asBoolean();
            pages++;
        }

        assertThat(receivedIds).containsExactlyInAnyOrder(
                "p-pull-1", "p-pull-2", "p-pull-3", "p-pull-4", "p-pull-5");
        assertThat(receivedIds).doesNotHaveDuplicates();
        assertThat(receivedGlobalSeqs).isSorted();
        assertThat(receivedGlobalSeqs).doesNotHaveDuplicates();
        assertThat(pages).isGreaterThan(1); // the feed really was paged
    }

    @Test
    @DisplayName("Pull is scoped: a node never receives another clinic's changes, but always receives central's")
    void pullFiltersByClinicScope() throws Exception {
        register("clinic-a-node", "clinic-a");
        register("clinic-b-node", "clinic-b");

        // Clinic A writes a patient (origin scope clinic-a)
        push("clinic-a-node", "clinic-a", change(1, "Patient", "p-private-a", "1-clinic-a-node", null,
                "clinic-a-node", "1700000000000:0:clinic-a-node", patientJson("p-private-a", "PrivateA")));

        // Central writes a patient directly (origin central)
        mockMvc.perform(post("/fhir/Patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientJson("p-central-made", "CentralMade")))
                .andExpect(status().isCreated());

        // Clinic B must see the central patient but NOT clinic A's private patient
        MvcResult result = mockMvc.perform(get("/api/v1/sync/pull")
                        .param("nodeId", "clinic-b-node")
                        .param("since", "0")
                        .param("limit", "500"))
                .andExpect(status().isOk())
                .andReturn();

        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> resourceIds = new ArrayList<>();
        tree.get("changes").forEach(c -> resourceIds.add(c.get("resourceId").asText()));
        assertThat(resourceIds).contains("p-central-made").doesNotContain("p-private-a");

        // Clinic A sees its own write (and the central one)
        MvcResult resultA = mockMvc.perform(get("/api/v1/sync/pull")
                        .param("nodeId", "clinic-a-node")
                        .param("since", "0")
                        .param("limit", "500"))
                .andExpect(status().isOk())
                .andReturn();
        var treeA = objectMapper.readTree(resultA.getResponse().getContentAsString());
        List<String> idsA = new ArrayList<>();
        treeA.get("changes").forEach(c -> idsA.add(c.get("resourceId").asText()));
        assertThat(idsA).contains("p-private-a", "p-central-made");
    }

    // ------------------------------------------------------------------
    // Concurrent branches (conflict routing)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A version whose parent is not the current head keeps both versions and opens a conflict case")
    void concurrentBranchIsKeptAndFlagged() throws Exception {
        register("branch-node", "clinic-branch");
        register("rival-node", "clinic-branch");

        // branch-node creates the resource and evolves it to v2
        push("branch-node", "clinic-branch",
                change(1, "Patient", "p-branch", "1-branch-node", null, "branch-node",
                        "1700000000000:0:branch-node", patientJson("p-branch", "Branch")),
                change(2, "Patient", "p-branch", "2-branch-node", "1-branch-node", "branch-node",
                        "1700000000001:0:branch-node", patientJson("p-branch", "BranchEdited")));

        // rival-node worked offline from v1 and pushes its own v2 concurrently
        push("rival-node", "clinic-branch",
                change(1, "Patient", "p-branch", "2-rival-node", "1-branch-node", "rival-node",
                        "1700000000001:1:rival-node", patientJson("p-branch", "RivalEdit")));

        Integer openCases = jdbcClient.sql("""
                SELECT COUNT(1) FROM conflict_case
                WHERE resource_type = 'Patient' AND resource_id = 'p-branch' AND status = 'OPEN'
                """).query(Integer.class).single();
        assertThat(openCases).isEqualTo(1);

        Boolean hasConflict = jdbcClient.sql("""
                SELECT has_conflict FROM resource_head
                WHERE resource_type = 'Patient' AND resource_id = 'p-branch'
                """).query(Boolean.class).single();
        assertThat(hasConflict).isTrue();

        // Both concurrent versions remain in history — nothing was overwritten.
        Integer versions = jdbcClient.sql("""
                SELECT COUNT(1) FROM resource_version
                WHERE resource_type = 'Patient' AND resource_id = 'p-branch'
                """).query(Integer.class).single();
        assertThat(versions).isEqualTo(3);

        // The resource still reads as exactly one Patient (newest by HLC for display)
        mockMvc.perform(get("/fhir/Patient/p-branch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name[0].family").value("RivalEdit"));
    }
}
