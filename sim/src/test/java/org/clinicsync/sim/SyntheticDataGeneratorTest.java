package org.clinicsync.sim;

import org.clinicsync.common.fhir.FhirUtil;
import org.clinicsync.sim.generator.SyntheticDataGenerator;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticDataGeneratorTest {

    @Test
    @DisplayName("Synthetic data generator should produce strictly valid FHIR R4 resources")
    void testSyntheticGenerationAndValidation() {
        // 1. Patient
        Patient patient = SyntheticDataGenerator.createSyntheticPatient("pat-001", "Amara", "Diallo", Enumerations.AdministrativeGender.FEMALE);
        String patientJson = FhirUtil.toJson(patient);
        Patient parsedPatient = (Patient) FhirUtil.validateAndParse("Patient", patientJson);
        assertThat(parsedPatient.getNameFirstRep().getFamily()).isEqualTo("Diallo");
        assertThat(parsedPatient.getGender()).isEqualTo(Enumerations.AdministrativeGender.FEMALE);

        // 2. Encounter
        Encounter encounter = SyntheticDataGenerator.createSyntheticEncounter("enc-001", "pat-001");
        String encounterJson = FhirUtil.toJson(encounter);
        Encounter parsedEncounter = (Encounter) FhirUtil.validateAndParse("Encounter", encounterJson);
        assertThat(parsedEncounter.getSubject().getReference()).isEqualTo("Patient/pat-001");
        assertThat(parsedEncounter.getStatus()).isEqualTo(Encounter.EncounterStatus.FINISHED);

        // 3. Observation (Vital Signs)
        Observation obs = SyntheticDataGenerator.createSyntheticObservation(
                "obs-hr-001", "pat-001", "enc-001", "8867-4", "Heart rate", 72.0, "/min"
        );
        String obsJson = FhirUtil.toJson(obs);
        Observation parsedObs = (Observation) FhirUtil.validateAndParse("Observation", obsJson);
        assertThat(parsedObs.getValueQuantity().getValue().doubleValue()).isEqualTo(72.0);
        assertThat(parsedObs.getCode().getCodingFirstRep().getCode()).isEqualTo("8867-4");

        // 4. AllergyIntolerance
        AllergyIntolerance allergy = SyntheticDataGenerator.createSyntheticAllergy(
                "alg-001", "pat-001", "373270004", "Penicillin"
        );
        String allergyJson = FhirUtil.toJson(allergy);
        AllergyIntolerance parsedAllergy = (AllergyIntolerance) FhirUtil.validateAndParse("AllergyIntolerance", allergyJson);
        assertThat(parsedAllergy.getPatient().getReference()).isEqualTo("Patient/pat-001");
        assertThat(parsedAllergy.getCode().getCodingFirstRep().getDisplay()).isEqualTo("Penicillin");
    }
}
