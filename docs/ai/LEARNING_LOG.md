# Learning Log & GSoC Prep: clinic_sync

> **Objective:** Document technical concepts learned, track areas needing reinforcement, and connect architectural patterns to real-world open-source health projects (e.g. OpenMRS, DHIS2, Apache).

---

## 1. Concepts Mastered / Understood
- *(To be populated as we progress through phases)*

---

## 2. Concepts In Progress / To Deepen
- **Distributed Clocks:** Lamport Timestamps, Vector Clocks, and Hybrid Logical Clocks (HLC) — causality vs. wall-clock time in partitioned systems.
- **HL7 FHIR R4 Specifications:** Resource models (`Patient`, `Observation`, `Encounter`, `AllergyIntolerance`, `Consent`, `AuditEvent`), bundle formats, and history semantics (`_history`).
- **Sync Topologies & Deltas:** Outbox pattern, change-data-capture (CDC), high-water marks, and idempotency tokens.
- **Conflict Resolution Strategies:** Field-level merges, commutative operations, three-way merges, and human review queues for clinical safety.
- **Testing Distributed Invariants:** Property-based testing with jqwik; chaos engineering and fault injection with Testcontainers.

---

## 3. GSoC & Open-Source Health Ecosystem Connections
- **OpenMRS (Open Medical Record System):**
  - *OpenMRS Sync 2.0 Module:* Built on Spring / FHIR, synchronizes data between parent and child servers (hub-and-spoke). Study their Atom Feed / REST push-pull implementation and conflict detection.
  - *OpenMRS FHIR2 Module:* Exposes modern FHIR R4 endpoints on top of OpenMRS data models.
- **DHIS2 (District Health Information System):**
  - *DHIS2 Android App (Offline-First):* Handles offline capture of aggregate and tracker data, synchronization queues, and local SQLite encryption via SQLCipher.
- **Apache Projects:**
  - *Apache Camel / ActiveMQ:* Enterprise integration patterns, message store-and-forward.
  - *Apache Kafka / Debezium:* Outbox pattern and change-data-capture concepts.

---

## 4. Reading List & References
- *Designing Data-Intensive Applications* by Martin Kleppmann (Chapters 5, 7, 8, 9 - Replication, Consistency, Clocks).
- *HL7 FHIR Specification (R4)*: https://hl7.org/fhir/R4/
- *HAPI FHIR Documentation*: https://hapifhir.io/hapi-fhir/docs/
- *OpenMRS Sync 2.0 Documentation*: https://wiki.openmrs.org/display/projects/Sync+2.0
