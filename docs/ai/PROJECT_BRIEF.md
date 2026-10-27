# Project Brief: clinic_sync (offline-fhir-sync)

> **Status:** Active / Accepted  
> **Last Updated:** 2026-10-27  

---

## 1. Executive Summary & Problem Statement
Rural and community healthcare facilities frequently operate with intermittent, low-bandwidth, or absent internet connectivity. Centralized Electronic Health Record (EHR) systems fail in these environments because they require continuous network availability. When local clinic workers revert to paper or isolated spreadsheets during network outages, clinical records become fragmented, leading to duplicated tests, missed critical allergies, conflicting medical histories, and compromised patient safety.

`clinic_sync` is an offline-first, HL7 FHIR-based patient record synchronization system. It empowers edge clinic nodes to perform full clinical read and write operations continuously while offline. When connectivity is restored, local changes synchronize bidirectionally with a central hub server without data loss, silent overwrites, or clinical record corruption.

---

## 2. Target Users & Personas

- **Rural Clinic Clinician / Nurse (Edge User):**
  - Needs zero-latency, uninterrupted access to record triage vitals, encounters, observations, and allergies on a clinic workstation or laptop.
  - Cannot be blocked by network timeouts or spinning loaders while attending to acute patients.
  - Needs clear, non-intrusive visibility into sync status and immediate awareness if a record requires clinical review.

- **Clinic Administrator (Edge Admin):**
  - Manages the local edge node installation, local encrypted storage status, and clinic-scoped configuration.
  - Monitors sync health, network throughput, and outbox drain status.

- **Central Health System Administrator / District Medical Officer (Central User):**
  - Oversees aggregated, consistent patient records across multiple remote edge clinics.
  - Reviews and resolves clinically ambiguous conflict cases in the central review queue.
  - Manages node identity, public key registrations, and edge device revocation.
  - Audits patient data access, merges, and sync history.

---

## 3. The 6 Critical Failure Scenarios

1. **Extended Edge Isolation (3+ Days):**
   A clinic edge node is completely disconnected from the central server for days. Clinicians continue registering patients, conducting visits, recording vitals, and updating allergies (accumulating dozens to hundreds of local transactions).
   - *Requirement:* Local operations remain 100% available. Upon reconnection, the edge outbox drains in bounded, gzip-compressed batches without memory exhaustion, socket timeout, or sequence gaps.

2. **Concurrent Conflicting Edits Across Clinics:**
   A patient visits Clinic Alpha in the morning and Clinic Beta in the afternoon while both clinics are partitioned. Clinic Alpha updates demographic address and records a penicillin allergy. Clinic Beta updates phone number and records penicillin allergy as refuted.
   - *Requirement:* Neither clinic's data is lost or overwritten silently. Demographic edits merge deterministically via Hybrid Logical Clock (HLC) field-level Last-Write-Wins with full audit history. The contradictory clinical allergy declaration triggers a `conflict_case` routed to the central clinical review queue.

3. **Mid-Sync Network Disconnect:**
   A batch of 50 resource versions is in flight from edge to central when the physical network drops at 60% completion.
   - *Requirement:* Transactional push guarantees all-or-nothing atomicity for the batch. Central only acknowledges the contiguous high-water mark `origin_seq`. Edge never advances its cursor for unacknowledged changes. Upon reconnection, sync resumes safely without duplicating or skipping changes (Idempotency and Resumability).

4. **Extreme Clock Skew / Erroneous Edge System Clock:**
   An edge workstation's motherboard CMOS battery is dead, causing the local hardware clock to reset to epoch (1970) or jump months into the future.
   - *Requirement:* Monotonic causality is preserved using Hybrid Logical Clocks (physical timestamp + logical counter + node ID). Central server rejects any incoming sync batch with an HLC timestamp exceeding a configurable future-drift bound (e.g. 5 minutes).

5. **Physical Device Theft / Stolen Edge Hardware:**
   An edge workstation or laptop is physically stolen from an unattended rural facility.
   - *Requirement:* The local H2 database is encrypted at rest using AES (`CIPHER=AES`). Without the filesystem encryption secret, patient records cannot be extracted. Central administrators can immediately revoke the edge node's public key in `node_registry`, causing central to reject any future sync attempts from that device.

6. **Duplicate Patient Registration Across Disconnected Clinics:**
   The same individual presents at two disconnected facilities under slightly differing names (e.g., "John Doe" vs. "Jon Doe", with identical birth date and phone).
   - *Requirement:* Disconnected facilities generate collision-free UUIDv7 identifiers. When synced to central, duplicate detection identifies candidate duplicates using blocking (DOB + phonetic soundex) and Jaro-Winkler similarity scoring. Administrators can merge records via FHIR `Patient.link`, with guaranteed reversibility without data loss.

---

## 4. Goals & Non-Goals

### Goals
- **Strict Offline Availability:** Edge nodes read and write all core FHIR resources with zero dependency on central connectivity.
- **Standards-Compliant FHIR R4:** Model data natively using HL7 FHIR R4 resources (`Patient`, `Encounter`, `Observation`, `AllergyIntolerance`, `Consent`, `AuditEvent`) using HAPI FHIR as a parsing/validation library.
- **Deterministic Convergence:** Hub-and-spoke sync protocol guaranteeing eventual consistency and no data loss under arbitrary partitions.
- **Clinical Safety First:** Zero silent data destruction. Append-only versioned DAG storage; all clinical contradictions open a human review case.
- **Provable Invariants:** Automated verification of the 8 core sync invariants via property-based testing (jqwik) and chaos fault injection.
- **GSoC Architecture Alignment:** Direct architectural relevance to OpenMRS Sync 2.0 and FHIR2 modules.

### Non-Goals
- Peer-to-peer (P2P) ad-hoc mesh sync between edge clinics (strictly hub-and-spoke in initial scope).
- Formal regulatory certification (HIPAA, CE mark, FDA SaMD, or India ABDM production sandbox compliance) — this is a prototype for portfolio and learning.
- Ingestion or storage of real patient health information (PHI/PII) — strictly synthetic data.
- Full EHR UI charting, billing, appointment scheduling, or pharmacy inventory management.

---

## 5. Success Metrics
- **Data Loss Rate:** Exactly 0.0% — every write acknowledged at the edge eventually reaches central.
- **Convergence Rate:** 100% state equivalence across replicas once sync drains and no new writes occur.
- **Conflict Safety:** 100% of contradictory clinical edits route to `conflict_case`; 0% silent overwrites.
- **Idempotency & Resumability:** Replaying identical sync batches or resuming dropped transfers causes 0 duplicate records and 0 corruption.
- **Test Invariant Coverage:** All 8 core invariants verified across at least 1,000 randomized property-based execution runs in jqwik.
