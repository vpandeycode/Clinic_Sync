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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CentralSyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Should accept pushed batch, record changes idempotently, and serve patient resource")
    void testPushAndRetrievePatient() throws Exception {
        String patientJson = """
                {
                    "resourceType": "Patient",
                    "id": "central-patient-101",
                    "name": [{"family": "Smith", "given": ["John"]}],
                    "gender": "male"
                }
                """;

        ChangeRecord change = new ChangeRecord(
                1L,
                "Patient",
                "central-patient-101",
                "1",
                patientJson,
                null,
                "clinic-alpha",
                "1729000000000:0:clinic-alpha",
                "dr-smith",
                false
        );

        SyncBatch batch = new SyncBatch("batch-alpha-1", "clinic-alpha", "clinic-alpha", List.of(change));

        // 1. First push
        mockMvc.perform(post("/api/v1/sync/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("batch-alpha-1"))
                .andExpect(jsonPath("$.highestContiguousOriginSeq").value(1))
                .andExpect(jsonPath("$.appliedCount").value(1));

        // 2. Query patient from central FHIR endpoint
        mockMvc.perform(get("/fhir/Patient/central-patient-101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("central-patient-101"))
                .andExpect(jsonPath("$.name[0].family").value("Smith"));

        // 3. Second push of same batch (Idempotency test)
        mockMvc.perform(post("/api/v1/sync/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("batch-alpha-1"))
                .andExpect(jsonPath("$.highestContiguousOriginSeq").value(1))
                .andExpect(jsonPath("$.appliedCount").value(0)); // 0 new applied, already present!
    }
}
