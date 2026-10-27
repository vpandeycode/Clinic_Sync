package org.clinicsync.sim.generator;

import org.clinicsync.common.fhir.FhirUtil;
import org.hl7.fhir.r4.model.*;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

/**
 * Generates valid, strictly synthetic FHIR R4 resources for simulation and testing.
 */
public final class SyntheticDataGenerator {

    private SyntheticDataGenerator() {}

    public static Patient createSyntheticPatient(String id, String givenName, String familyName, Enumerations.AdministrativeGender gender) {
        Patient patient = new Patient();
        patient.setId(id != null ? id : UUID.randomUUID().toString());
        patient.addIdentifier()
                .setSystem("http://clinic.org/patients")
                .setValue("MRN-" + patient.getIdElement().getIdPart());
        patient.addName()
                .setFamily(familyName)
                .addGiven(givenName);
        patient.setGender(gender);
        patient.setBirthDate(new Date(System.currentTimeMillis() - 30L * 365 * 24 * 3600 * 1000)); // ~30 years old
        patient.setActive(true);
        return patient;
    }

    public static Encounter createSyntheticEncounter(String id, String patientId) {
        Encounter encounter = new Encounter();
        encounter.setId(id != null ? id : UUID.randomUUID().toString());
        encounter.setStatus(Encounter.EncounterStatus.FINISHED);
        encounter.setClass_(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", "ambulatory"));
        encounter.setSubject(new Reference("Patient/" + patientId));
        Period period = new Period();
        period.setStart(new Date(System.currentTimeMillis() - 3600 * 1000));
        period.setEnd(new Date());
        encounter.setPeriod(period);
        return encounter;
    }

    public static Observation createSyntheticObservation(String id, String patientId, String encounterId, String code, String display, double value, String unit) {
        Observation obs = new Observation();
        obs.setId(id != null ? id : UUID.randomUUID().toString());
        obs.setStatus(Observation.ObservationStatus.FINAL);
        obs.addCategory().addCoding()
                .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
                .setCode("vital-signs")
                .setDisplay("Vital Signs");
        obs.getCode().addCoding()
                .setSystem("http://loinc.org")
                .setCode(code)
                .setDisplay(display);
        obs.setSubject(new Reference("Patient/" + patientId));
        if (encounterId != null) {
            obs.setEncounter(new Reference("Encounter/" + encounterId));
        }
        obs.setEffective(new DateTimeType(new Date()));
        obs.setValue(new Quantity()
                .setValue(BigDecimal.valueOf(value))
                .setUnit(unit)
                .setSystem("http://unitsofmeasure.org")
                .setCode(unit));
        return obs;
    }

    public static AllergyIntolerance createSyntheticAllergy(String id, String patientId, String substanceCode, String substanceDisplay) {
        AllergyIntolerance allergy = new AllergyIntolerance();
        allergy.setId(id != null ? id : UUID.randomUUID().toString());
        allergy.setClinicalStatus(new CodeableConcept().addCoding(
                new Coding("http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical", "active", "Active")
        ));
        allergy.setVerificationStatus(new CodeableConcept().addCoding(
                new Coding("http://terminology.hl7.org/CodeSystem/allergyintolerance-verification", "confirmed", "Confirmed")
        ));
        allergy.setCode(new CodeableConcept().addCoding(
                new Coding("http://snomed.info/sct", substanceCode, substanceDisplay)
        ));
        allergy.setPatient(new Reference("Patient/" + patientId));
        allergy.setRecordedDate(new Date());
        return allergy;
    }
}
