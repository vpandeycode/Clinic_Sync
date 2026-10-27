package org.clinicsync.common.fhir;

import org.clinicsync.common.model.ResourceVersionRecord;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FhirUtilTest {

    @Test
    @DisplayName("Should parse and serialize valid FHIR Patient resource")
    void testPatientSerialization() {
        Patient patient = new Patient();
        patient.setId("pat-1");
        patient.addName().setFamily("Smith").addGiven("Alice");

        String json = FhirUtil.toJson(patient);
        assertThat(json).contains("Alice");
        assertThat(json).contains("Smith");

        IBaseResource parsed = FhirUtil.validateAndParse("Patient", json);
        assertThat(parsed).isInstanceOf(Patient.class);
    }

    @Test
    @DisplayName("Should reject invalid FHIR resource or type mismatch")
    void testInvalidFhirRejection() {
        String invalidJson = "{ \"resourceType\": \"Encounter\", \"id\": \"enc-1\" }";
        assertThatThrownBy(() -> FhirUtil.validateAndParse("Patient", invalidJson))
                .isInstanceOf(org.clinicsync.common.exception.InvalidFhirException.class)
                .hasMessageContaining("Resource type mismatch");

        assertThatThrownBy(() -> FhirUtil.validateAndParse("Patient", "not a json"))
                .isInstanceOf(org.clinicsync.common.exception.InvalidFhirException.class);
    }

    @Test
    @DisplayName("Should assemble valid FHIR history Bundle with tombstones")
    void testHistoryBundleCreation() {
        ResourceVersionRecord v1 = new ResourceVersionRecord(
                "Patient", "pat-1", "1",
                "{\"resourceType\":\"Patient\",\"id\":\"pat-1\"}",
                null, "edge-1", 1L, "100:0:edge-1", "doc", false, Instant.now().minusSeconds(60)
        );
        ResourceVersionRecord v2 = new ResourceVersionRecord(
                "Patient", "pat-1", "2",
                "{\"resourceType\":\"Patient\",\"id\":\"pat-1\"}",
                "1", "edge-1", 2L, "200:0:edge-1", "doc", true, Instant.now()
        );

        Bundle bundle = FhirUtil.createHistoryBundle("Patient", "pat-1", List.of(v1, v2));
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.HISTORY);
        assertThat(bundle.getEntry()).hasSize(2);
        assertThat(bundle.getEntry().get(0).getResponse().getStatus()).isEqualTo("200 OK");
        assertThat(bundle.getEntry().get(1).getResponse().getStatus()).isEqualTo("204 No Content");
    }
}
