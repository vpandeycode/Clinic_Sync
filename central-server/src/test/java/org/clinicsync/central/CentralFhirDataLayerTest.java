package org.clinicsync.central;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CentralFhirDataLayerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Comprehensive FHIR lifecycle on central: create, update, history, concurrency, tombstone")
    void testCompleteFhirLifecycleOnCentral() throws Exception {
        String patientId = "pat-central-200";
        String initialJson = """
                {
                    "resourceType": "Patient",
                    "id": "%s",
                    "name": [{"family": "Kowalski", "given": ["Jan"]}],
                    "gender": "male"
                }
                """.formatted(patientId);

        // 1. Create Patient
        mockMvc.perform(post("/fhir/Patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialJson))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(header().string(HttpHeaders.LOCATION, "/fhir/Patient/" + patientId))
                .andExpect(jsonPath("$.name[0].family").value("Kowalski"));

        // 2. Read Patient
        mockMvc.perform(get("/fhir/Patient/" + patientId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));

        // 3. Update Patient with valid If-Match
        String updatedJson = """
                {
                    "resourceType": "Patient",
                    "id": "%s",
                    "name": [{"family": "Kowalski", "given": ["Jan", "Piotr"]}],
                    "gender": "male"
                }
                """.formatted(patientId);

        mockMvc.perform(put("/fhir/Patient/" + patientId)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedJson))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(jsonPath("$.name[0].given[1]").value("Piotr"));

        // 4. Verify History & Update Never Overwrites Old Version
        mockMvc.perform(get("/fhir/Patient/" + patientId + "/_history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.entry", hasSize(2)))
                .andExpect(jsonPath("$.entry[0].response.etag").value("\"1\""))
                .andExpect(jsonPath("$.entry[1].response.etag").value("\"2\""));

        // 5. Test Optimistic Concurrency: Stale If-Match fails with 412
        mockMvc.perform(put("/fhir/Patient/" + patientId)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedJson))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"));

        // 6. Test Invalid FHIR Rejection (400 Bad Request)
        mockMvc.perform(post("/fhir/Patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"Encounter\"}")) // type mismatch
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"));

        // 7. Soft Delete (Tombstone)
        mockMvc.perform(delete("/fhir/Patient/" + patientId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""));

        // 8. Subsequent GET returns 410 Gone
        mockMvc.perform(get("/fhir/Patient/" + patientId))
                .andExpect(status().isGone());

        // 9. History still preserves all 3 versions (including tombstone)
        mockMvc.perform(get("/fhir/Patient/" + patientId + "/_history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.entry[2].response.status").value("204 No Content"));
    }
}
