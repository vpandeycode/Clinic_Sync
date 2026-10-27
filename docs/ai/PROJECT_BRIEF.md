# Project Brief: clinic_sync

> **Status:** Draft / Phase 0 Discovery  
> **Last Updated:** 2026-10-27  

---

## 1. Executive Summary & Problem Statement
Rural and remote healthcare facilities frequently operate with intermittent, low-bandwidth, or completely absent internet connectivity. Existing centralized Electronic Health Record (EHR) systems fail in these environments because they require constant cloud connectivity. When local clinic workers revert to paper or isolated spreadsheets during outages, records become fragmented, leading to duplicated interventions, missed allergies, conflicting medical histories, and compromised patient safety.

`clinic_sync` is an offline-first, HL7 FHIR-based patient record synchronization system. It allows edge clinic nodes to perform full clinical read and write operations continuously while offline. When connectivity is available, changes synchronize bidirectionally with a central hub server without data loss, silent overwrite, or clinical record corruption.

---

## 2. Target Users & Personas
- **Rural Clinic Clinician / Nurse (Edge User):**
  - Needs fast, uninterrupted access to record vitals, encounters, observations, and allergies on a local machine/tablet.
  - Cannot wait for network timeouts when attending to patients.
  - Needs clear indicators of sync status and non-intrusive notification if a record requires clinical review.
- **Central Hospital / District Health Administrator (Central User):**
  - Needs aggregated, consistent view of patient records across multiple remote clinics.
  - Reviews and resolves flagged clinical conflicts (e.g., conflicting diagnoses or medications recorded at different clinics).
  - Monitors clinic sync health and data integrity.

---

## 3. Goals & Non-Goals

### Goals
- **Offline-First Resilience:** Zero read/write downtime at edge clinics during complete network loss.
- **FHIR Standards Compliance:** Native modeling using HL7 FHIR (R4) core resources (`Patient`, `Encounter`, `Observation`, `AllergyIntolerance`, `Consent`, `AuditEvent`).
- **Deterministic Sync & Convergence:** Eventual consistency across central and edge nodes using verifiable sync invariants.
- **Clinical Safety First:** Never silently overwrite conflicting clinical data; preserve complete version history and provenance.
- **GSoC Architecture Alignment:** Provide direct learning value for open-source health projects (e.g., OpenMRS Sync 2.0 module, DHIS2 Android capture).

### Non-Goals
- Peer-to-peer (P2P) clinic-to-clinic ad-hoc mesh sync (strictly hub-and-spoke in initial scope).
- Production regulatory compliance certification (FDA SaMD, HIPAA certification, India ABDM sandbox production clearance) — this is a prototype for demonstration, portfolio, and learning.
- Real patient data usage (strictly synthetic data).
- Complex billing, scheduling, or pharmacy inventory management.

---

## 4. Primary Failure Scenarios (To Refine in Phase 0)
1. **Extended Edge Isolation:** Clinic edge node disconnected for 3+ days while registering new patients and logging visits.
2. **Concurrent Conflicting Edits:** Patient visits Clinic A on Monday morning and Clinic B on Monday afternoon before Clinic A has synced; both record conflicting clinical observations.
3. **Mid-Sync Interruption:** Network drops halfway through a large multi-resource sync payload transfer.
4. **Clock Skew / Drift:** Edge clinic machine has an incorrect system clock (e.g., CMOS battery dead, off by months).
5. **Edge Hardware Loss / Theft:** Device lost or stolen with local cached clinical database.
6. **Duplicate Patient Creation:** Same individual registered at two different clinics under slightly varying demographic spellings while offline.

---

## 5. Success Metrics
- **Data Loss Rate:** 0% (every acknowledged local write eventually reaches central).
- **Conflict Transparency:** 100% of conflicting clinical edits flagged for human review or deterministically merged without data overwrite.
- **Sync Convergence:** After reconnection and sync exchange, both edge and central represent identical authoritative states.
- **Recovery Time:** Edge service restarts after power cut / hard shutdown and resumes sync cleanly without corrupted journals.
