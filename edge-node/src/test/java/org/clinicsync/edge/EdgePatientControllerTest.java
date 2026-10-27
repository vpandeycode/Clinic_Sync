package org.clinicsync.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EdgePatientControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Should create Patient locally on edge and retrieve it successfully")
    void testCreateAndGetPatient() throws Exception {
        String patientJson = """
                {
                    "resourceType": "Patient",
                    "id": "test-patient-001",
                    "name": [{"family": "Doe", "given": ["Jane"]}],
                    "gender": "female",
                    "birthDate": "1990-05-15"
                }
                """;

        mockMvc.perform(post("/fhir/Patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientJson))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Location", "/fhir/Patient/test-patient-001"))
                .andExpect(jsonPath("$.id").value("test-patient-001"));

        mockMvc.perform(get("/fhir/Patient/test-patient-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name[0].family").value("Doe"))
                .andExpect(jsonPath("$.gender").value("female"));
    }
}
