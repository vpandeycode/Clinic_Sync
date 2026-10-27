package org.clinicsync.edge;

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
class EdgeFhirDataLayerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Comprehensive FHIR lifecycle on edge: create, update, history, concurrency, tombstone")
    void testCompleteFhirLifecycleOnEdge() throws Exception {
        String patientId = "pat-edge-100";
        String initialJson = """
                {
                    "resourceType": "Patient",
                    "id": "%s",
                    "name": [{"family": "O'Connor", "given": ["Liam"]}],
                    "gender": "male",
                    "birthDate": "1995-04-12"
                }
                """.formatted(patientId);

        // 1. Create Patient
        mockMvc.perform(post("/fhir/Patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialJson))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(header().string(HttpHeaders.LOCATION, "/fhir/Patient/" + patientId))
                .andExpect(jsonPath("$.name[0].family").value("O'Connor"));

        // 2. Read Patient
        mockMvc.perform(get("/fhir/Patient/" + patientId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.gender").value("male"));

        // 3. Update Patient with valid If-Match
        String updatedJson = """
                {
                    "resourceType": "Patient",
                    "id": "%s",
                    "name": [{"family": "O'Connor", "given": ["Liam", "Patrick"]}],
                    "gender": "male",
                    "birthDate": "1995-04-12"
                }
                """.formatted(patientId);

        mockMvc.perform(put("/fhir/Patient/" + patientId)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedJson))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(jsonPath("$.name[0].given[1]").value("Patrick"));

        // 4. Verify History & Update Never Overwrites Old Version
        mockMvc.perform(get("/fhir/Patient/" + patientId + "/_history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("Bundle"))
                .andExpect(jsonPath("$.type").value("history"))
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
        String invalidJson = "{ \"resourceType\": \"Patient\", \"name\": \"NotAnArray\" }";
        mockMvc.perform(put("/fhir/Patient/" + patientId)
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"));

        // 7. Soft Delete (Tombstone)
        mockMvc.perform(delete("/fhir/Patient/" + patientId)
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""));

        // 8. Subsequent GET returns 410 Gone
        mockMvc.perform(get("/fhir/Patient/" + patientId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"));

        // 9. History still preserves all 3 versions (including tombstone)
        mockMvc.perform(get("/fhir/Patient/" + patientId + "/_history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.entry", hasSize(3)))
                .andExpect(jsonPath("$.entry[2].response.status").value("204 No Content"));
    }

    @Test
    @DisplayName("Should create and read Observation, Encounter, and AllergyIntolerance on edge")
    void testOtherResourceTypes() throws Exception {
        // Observation
        String obsJson = """
                {
                    "resourceType": "Observation",
                    "id": "obs-bp-1",
                    "status": "final",
                    "code": {
                        "coding": [{"system": "http://loinc.org", "code": "8480-6", "display": "Systolic BP"}]
                    },
                    "subject": {"reference": "Patient/pat-1"}
                }
                """;

        mockMvc.perform(post("/fhir/Observation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(obsJson))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));

        // Encounter
        String encJson = """
                {
                    "resourceType": "Encounter",
                    "id": "enc-visit-1",
                    "status": "finished",
                    "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "AMB"}
                }
                """;

        mockMvc.perform(post("/fhir/Encounter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(encJson))
                .andExpect(status().isCreated());

        // AllergyIntolerance
        String allergyJson = """
                {
                    "resourceType": "AllergyIntolerance",
                    "id": "allergy-pen-1",
                    "clinicalStatus": {
                        "coding": [{"system": "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical", "code": "active"}]
                    },
                    "verificationStatus": {
                        "coding": [{"system": "http://terminology.hl7.org/CodeSystem/allergyintolerance-verification", "code": "confirmed"}]
                    },
                    "patient": {"reference": "Patient/pat-1"}
                }
                """;

        mockMvc.perform(post("/fhir/AllergyIntolerance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(allergyJson))
                .andExpect(status().isCreated());
    }
}
