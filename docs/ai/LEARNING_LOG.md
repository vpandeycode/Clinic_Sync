# Learning Log & GSoC Prep: clinic_sync

> **Objective:** Document technical concepts learned, track areas needing reinforcement, and connect architectural patterns to real-world open-source health projects (specifically **OpenMRS**).

---

## 1. Concepts Mastered / Understood
- **Java 21 Fundamentals:** Modern language constructs, pattern matching, records, virtual threads.
- **Spring Boot Dependency Injection:** Inversion of control, `@Service`, `@Repository`, Spring configuration.

---

## 2. Concepts In Progress / To Study

### A. HL7 FHIR (Fast Healthcare Interoperability Resources - R4)
- **What is FHIR?** An international standard for healthcare data exchange using modern RESTful web APIs and JSON/XML.
- **Resources to Learn:**
  - `Patient`: Human demographic master record.
  - `Encounter`: The clinical interaction (clinic visit).
  - `Observation`: Single measurement or clinical assertion (heart rate, blood pressure, temperature, SpO2).
  - `AllergyIntolerance`: Risk of adverse reaction to substances (e.g. penicillin).
  - `Consent`: Patient privacy and record-sharing directives.
  - `AuditEvent`: Tamper-evident record of data access and modifications.
- **Key Mechanism:** FHIR resource versioning (`_history`), Bundles (`transaction`, `batch`), and Provenance.

### B. Distributed Systems & Synchronization
- **Hybrid Logical Clocks (HLC):**
  - Physical wall clocks cannot be trusted (CMOS batteries die, NTP steps, drift).
  - Pure logical clocks (Lamport) track causality but lose relationship to real time.
  - HLC combines physical time bounds ($l$) with a logical monotonic counter ($c$) to provide deterministic causality order while staying within bounded error of real physical time.
- **Transactional Outbox Pattern:**
  - Writing business data and a sync event into the *same* local database transaction.
  - Prevents dual-write hazards (e.g., saving a patient locally but crashing before publishing to the sync network).
- **Idempotency Tokens & High-Water Marks:**
  - Guaranteeing safe retries over flaky cellular/satellite networks without duplicate records.

---

## 3. OpenMRS Ecosystem Alignment
- **OpenMRS Sync 2.0:**
  - Designed for hub-and-spoke multi-tier hospital and health post synchronization.
  - Uses push/pull REST feeds (Atom feed / FHIR bundles).
  - Study how OpenMRS tracks change events: `SyncMessage`, `SyncAudit`, and event push/pull handlers.
- **OpenMRS FHIR2 Module:**
  - Translates internal OpenMRS Hibernate entities into FHIR R4 resources using HAPI FHIR.
  - Our work directly mimics this layer in `clinic-sync-edge` and `clinic-sync-central`.

---

## 4. Reading List & References
- *Designing Data-Intensive Applications* by Martin Kleppmann (Chapters 5: Replication, 8: Trouble with Distributed Systems, 9: Consistency and Consensus).
- *Logical Physical Clocks and Consistent Snapshots in Globally Distributed Databases* (Kulkarni et al., 2014) - Original paper on HLC.
- *HL7 FHIR R4 Quickstart*: https://hl7.org/fhir/R4/overview-dev.html
- *HAPI FHIR Plain Server & Parser Docs*: https://hapifhir.io/hapi-fhir/docs/
- *OpenMRS Sync 2.0 Architecture*: https://wiki.openmrs.org/display/projects/Sync+2.0
