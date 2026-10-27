# Architecture Decision Records (ADRs): clinic_sync

This document records the architectural decisions, trade-offs, and assumptions governing `clinic_sync`.

---

## ADR Index

| ID | Title | Status | Date |
|---|---|---|---|
| ADR-0001 | Use ADRs for Architecture Governance and Traceability | Accepted | 2026-10-27 |
| ADR-0002 | Technology Stack: Java 21, Spring Boot 3.x, and Maven Multi-Module Architecture | Accepted | 2026-10-27 |
| ADR-0003 | HAPI FHIR as Library Only (No HAPI JPA Server) with Custom Versioned Store | Accepted | 2026-10-27 |
| ADR-0004 | Polyglot Storage: PostgreSQL (Central) and AES-Encrypted H2 File DB (Edge) | Accepted | 2026-10-27 |
| ADR-0005 | Spring JDBC (JdbcClient) over JPA for Explicit SQL and Transaction Boundaries | Accepted | 2026-10-27 |
| ADR-0006 | Hub-and-Spoke Topology with Push-Pull Delta Sync Protocol | Accepted | 2026-10-27 |
| ADR-0007 | REST + Gzip-Compressed JSON Transport | Accepted | 2026-10-27 |
| ADR-0008 | Hybrid Logical Clock (HLC) for Causality and Drift-Bounded Ordering | Accepted | 2026-10-27 |
| ADR-0009 | Client-Generated UUIDv7 for Resource Identity | Accepted | 2026-10-27 |
| ADR-0010 | Ed25519 Cryptographic Payload Signing via Standard JDK java.security | Accepted | 2026-10-27 |
| ADR-0011 | FHIR R4 Core Resource Scope (Patient, Encounter, Observation, AllergyIntolerance, Consent, AuditEvent) | Accepted | 2026-10-27 |
| ADR-0012 | Append-Only Versioned Resource Store with Soft Deletes (Tombstones) | Accepted | 2026-10-27 |
| ADR-0013 | 3-Tier Merge Strategy and Conflict Review Case Queue | Accepted | 2026-10-27 |
| ADR-0014 | Deterministic and Probabilistic Patient Deduplication via Patient.link | Accepted | 2026-10-27 |
| ADR-0015 | Assumptions on Node Sequence Density and Canonical JSON Serialization | Accepted | 2026-10-27 |

---

### ADR-0001: Use ADRs for Architecture Governance and Traceability
- **Status:** Accepted
- **Context:** Complex distributed healthcare data synchronization involves subtle trade-offs between availability, consistency, clinical safety, and performance.
- **Decision:** Document all architectural choices as structured ADRs in `docs/ai/DECISIONS.md`.
- **Consequences:** Decisions are auditable, versioned with code, and explain the "why" for portfolio and GSoC reviewers.

---

### ADR-0002: Technology Stack: Java 21, Spring Boot 3.x, and Maven Multi-Module Architecture
- **Status:** Accepted
- **Context:** The system needs strong typing, modern concurrency (virtual threads), enterprise DI, modular isolation, and alignment with open-source health projects (OpenMRS).
- **Decision:** Use Java 21 LTS, Spring Boot 3.x, and a 4-module Maven layout:
  - `common`: Pure Java, zero Spring dependencies (HLC, models, canonical JSON, crypto helpers).
  - `edge-node`: Spring Boot edge service with encrypted H2 and local sync agent.
  - `central-server`: Spring Boot central server with PostgreSQL, conflict engine, and identity resolution.
  - `sim`: Simulation harness with fault injection, jqwik property tests, and benchmarks.
- **Consequences:** Clean separation of concerns; domain logic in `common` remains completely testable without Spring container overhead.

---

### ADR-0003: HAPI FHIR as Library Only (No HAPI JPA Server) with Custom Versioned Store
- **Status:** Accepted
- **Context:** HAPI FHIR provides an out-of-the-box JPA server, but its internal schema is complex, heavy, opinionated, and difficult to embed inside a lightweight encrypted edge database or adapt to offline DAG replication.
- **Decision:** Use HAPI FHIR exclusively as a library for model POJOs, JSON parsing, serialization, and validation (`FhirContext.forR4()`). Persistence is handled by our custom versioned relational tables (`resource_version`, `resource_head`).
- **Consequences:** Full control over relational schemas, indices, version DAGs, and sync transactions, without HAPI JPA abstraction overhead.

---

### ADR-0004: Polyglot Storage: PostgreSQL (Central) and AES-Encrypted H2 File DB (Edge)
- **Status:** Accepted
- **Context:** Central server requires high concurrency, robust relational indexing, and ACID compliance under concurrent edge pushes. Edge nodes run on local clinic hardware with unreliable power and physical security risks.
- **Decision:** Use PostgreSQL 16+ on Central. Use H2 file database on Edge with built-in AES disk encryption (`CIPHER=AES`).
- **Consequences:** Edge data is protected at rest against physical hardware theft without requiring external OS-level encryption or native JNI libraries (like SQLCipher). Central benefits from production-grade PostgreSQL features.

---

### ADR-0005: Spring JDBC (JdbcClient) over JPA for Explicit SQL and Transaction Boundaries
- **Status:** Accepted
- **Context:** ORM layers (Hibernate/JPA) introduce dirty checking, lazy loading issues, hidden N+1 queries, and opaque transaction semantics, which are dangerous for precise append-only version DAG management.
- **Decision:** Use Spring 6 / Spring Boot 3's fluent `JdbcClient` (and `JdbcTemplate`) for all database access. Schema is managed via Flyway.
- **Consequences:** Clear, explicit SQL queries and total control over database transactions and batch commits.

---

### ADR-0006: Hub-and-Spoke Topology with Push-Pull Delta Sync Protocol
- **Status:** Accepted
- **Context:** Rural clinics communicate with a central district hospital. Direct peer-to-peer (P2P) mesh networking introduces extreme routing complexity, variable NAT traversal issues, and distributed consensus overhead unnecessary for the clinic domain.
- **Decision:** Standardize on Hub-and-Spoke topology. Edge nodes push local uncommitted changes from their `change_log` to Central, then pull downstream deltas using a `global_seq` high-water mark.
- **Consequences:** Simplifies convergence verification, aligns with OpenMRS Sync 2.0, and bounds network topologies.

---

### ADR-0007: REST + Gzip-Compressed JSON Transport
- **Status:** Accepted
- **Context:** Edge networks operate over low-bandwidth 2G/3G cellular or satellite uplinks.
- **Decision:** Use standard HTTP/1.1 REST endpoints with gzip-compressed JSON request and response bodies.
- **Consequences:** Drastically reduces byte payloads over the wire; standard HTTP tooling and debugging work out of the box.

---

### ADR-0008: Hybrid Logical Clock (HLC) for Causality and Drift-Bounded Ordering
- **Status:** Accepted
- **Context:** Physical wall clocks on edge nodes drift, experience NTP step jumps, or reset upon battery loss. Pure logical clocks (Lamport) lack real-world timestamp correlation necessary for clinical audit.
- **Decision:** Implement Hybrid Logical Clocks (HLC) combining physical epoch milliseconds $l$, logical counter $c$, and node ID. Central rejects batches exceeding a 5-minute future drift bound.
- **Consequences:** Monotonic, causal ordering is guaranteed even during clock skew, while preserving physical timestamp correlation.

---

### ADR-0009: Client-Generated UUIDv7 for Resource Identity
- **Status:** Accepted
- **Context:** Offline nodes must create resources without coordinating with central. Sequential integers (`1, 2, 3`) collide immediately. Random UUIDv4 causes B-tree index fragmentation.
- **Decision:** Generate client-assigned UUIDv7 (RFC 9562) for all FHIR resource IDs.
- **Consequences:** 128-bit collision-free decentralized generation with time-ordered prefixes that preserve B-tree index locality.

---

### ADR-0010: Ed25519 Cryptographic Payload Signing via Standard JDK java.security
- **Status:** Accepted
- **Context:** Sync payloads must be protected against tampering, forgery, and unauthorized edge nodes. Custom crypto must never be hand-rolled.
- **Decision:** Use standard JDK `java.security.Signature.getInstance("Ed25519")` (available built-in since Java 15). Each edge node has an Ed25519 keypair registered in Central's `node_registry`. Batches are signed over their Canonical JSON representation.
- **Consequences:** High-performance, constant-time, collision-resistant signatures using standard JDK libraries with zero external dependencies.

---

### ADR-0011: FHIR R4 Core Resource Scope
- **Status:** Accepted
- **Context:** Real-world EHR systems encompass hundreds of resource types. We must avoid scope creep while modeling a realistic outpatient triage workflow.
- **Decision:** Standardize on 6 core FHIR R4 resources:
  - `Patient`: Demographics, identifiers, links.
  - `Encounter`: Clinic visits and triage consultations.
  - `Observation`: Vital signs (HR, BP, Temp, RR, SpO2, Weight).
  - `AllergyIntolerance`: Critical allergy screening.
  - `Consent`: Data-sharing policies.
  - `AuditEvent`: Provenance, access tracking, and sync event logs.
- **Consequences:** Focused clinical domain; sufficient depth to demonstrate all sync invariants without boilerplate bloat.

---

### ADR-0012: Append-Only Versioned Resource Store with Soft Deletes (Tombstones)
- **Status:** Accepted
- **Context:** Clinical data must never be lost or silently overwritten. Hard deletes destroy history and break auditability.
- **Decision:** Store all modifications in an append-only `resource_version` table. Updates create a new version with parent pointers. Deletions create a tombstone version (`is_tombstone = true`). The active head is tracked in `resource_head`.
- **Consequences:** Invariant 5 (History Preserved) is guaranteed at the architectural level. Full historical replay is possible.

---

### ADR-0013: 3-Tier Merge Strategy and Conflict Review Case Queue
- **Status:** Accepted
- **Context:** Offline concurrent edits inevitably diverge. Silent Last-Write-Wins (LWW) on clinical assertions can be fatal (e.g. overwriting a severe allergy).
- **Decision:** Implement a 3-tier merge strategy:
  1. Additive: `Encounter` and `Observation` are append-only.
  2. Demographics LWW: Field-level LWW by HLC for non-critical demographic fields.
  3. Clinical Contradictions: Competing clinical declarations set `has_conflict = true` and create a `conflict_case` for clinician review.
- **Consequences:** Zero silent corruption of clinical meaning; full transparency for human decision-makers.

---

### ADR-0014: Deterministic and Probabilistic Patient Deduplication via Patient.link
- **Status:** Accepted
- **Context:** The same patient may register at multiple offline clinics under slight demographic spelling variations.
- **Decision:** Detect candidate duplicates using blocking (Date of Birth + Soundex phonetic encoding) followed by Jaro-Winkler string similarity scoring. Merges are performed non-destructively using FHIR `Patient.link` (`replaces`/`replaced-by`), fully reversible via an unmerge operation.
- **Consequences:** Invariant 8 (Reversibility) is preserved; records can be unlinked at any time without database corruption.

---

### ADR-0015: Assumptions on Node Sequence Density and Canonical JSON Serialization
- **Status:** Accepted
- **Context:** Edge outboxes generate `origin_seq` sequence numbers. Payloads must have deterministic byte hashes for Ed25519 signature verification.
- **Decision:** 
  1. `origin_seq` is assumed to be strictly dense and sequential per node (`1, 2, 3...`). Central tracks `applied_changes` and acknowledges the highest contiguous sequence.
  2. Canonical JSON sorts object keys alphabetically and strips extraneous whitespace before computing Ed25519 signatures.
- **Consequences:** Ensures deterministic cryptographic verification and gap detection across all platforms.
