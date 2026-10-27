# Project Roadmap & Phases: clinic_sync

> **Status:** Phase 0 Active  
> **Last Updated:** 2026-10-27  

Each phase has strict acceptance criteria. A phase cannot be marked complete until all checkboxes are checked, all verification tests pass, and the phase summary is posted.

---

### Phase 0: Docs and Decisions (Current Phase)
- [x] Create and align `PROJECT_BRIEF.md` (users, 6 failure scenarios, non-goals, success metrics).
- [x] Create `ARCHITECTURE.md` (hub-and-spoke topology, module layout, data models, sync protocol, Mermaid diagrams).
- [x] Create `SYNC_INVARIANTS.md` (all 8 invariants with formal definitions, violation scenarios, and verification strategies).
- [x] Create `PHASES.md` (concrete roadmap with checkboxes and acceptance criteria).
- [x] Create `DECISIONS.md` (record ADRs for all 14 fixed decisions plus core assumptions).
- [x] Create `HANDOFF.md` (current state snapshot and next steps).
- [x] Create `docs/learning/phase-0.md` (learning concepts, architecture choices, official study references).
- **Acceptance Criteria:** Files exist, are consistent with prompt specifications, and are committed.

---

### Phase 1: Foundation and Walking Skeleton (Completed)
- [x] Multi-module parent POM (`common`, `edge-node`, `central-server`, `sim`).
- [x] Maven dependency management, Java 21, Spring Boot 3.x, Spotless / Checkstyle formatting.
- [x] CI workflow (`.github/workflows/ci.yml`) for automated build and verification.
- [x] Docker Compose (`docker-compose.yml`) running central server + PostgreSQL + 2 edge nodes.
- [x] Actuator health endpoints on edge and central.
- [x] Flyway baseline migrations for schema creation (`db/migration/edge` and `db/migration/central`).
- [x] Test harness for edge and central with isolated schemas.
- [x] Naive walking skeleton: A `Patient` resource POSTed to Edge Node A appears on Central Server via push sync.
- **Acceptance Criteria:** `docker compose up` works, naive Patient flow works end to end, `mvn verify` passes locally and in CI config.

---

### Phase 2: FHIR Data Layer (Completed)
- [x] Integrate HAPI FHIR (R4) as a parsing, serialization, and validation library (no HAPI JPA server).
- [x] Implement client-generated UUIDv7 generator for resource IDs.
- [x] Implement versioned resource store (`resource_version` and `resource_head`) using Spring JDBC (`JdbcClient`) for `Patient`, `Encounter`, `Observation`, and `AllergyIntolerance`.
- [x] Implement history endpoint (`GET /fhir/{resourceType}/{id}/_history`).
- [x] Implement soft-delete / tombstones (`is_tombstone = true`).
- [x] Implement optimistic concurrency control via HTTP `If-Match` on version IDs.
- [x] Build synthetic FHIR clinical data generator in `sim`.
- **Acceptance Criteria:** Create, update, read, history, and tombstone-delete work on both edge and central; an update never overwrites an old version; tests cover invalid FHIR rejection and concurrent update handling.

---

### Phase 3: Sync Core
- [ ] Implement Hybrid Logical Clock (HLC) in `common` (physical ms + counter + node id).
- [ ] Property-based tests for HLC monotonicity and future drift bounds.
- [ ] Implement Canonical JSON serialization and Ed25519 payload signing helpers in `common`.
- [ ] Implement edge `change_log` outbox and `sync_state`.
- [ ] Implement Central sync push endpoint with dense `origin_seq` batching, idempotency checks (`applied_changes`), and contiguous sequence ACKs.
- [ ] Implement Central sync pull endpoint with `global_seq` cursor and paging.
- [ ] Implement edge sync client agent with retry logic, exponential backoff, and jitter.
- [ ] Implement selective sync filtered by clinic scope.
- [ ] Implement central node registration endpoint.
- **Acceptance Criteria:** Two edge nodes converge through central under normal and interrupted syncs; Invariants 1 to 4 pass as automated tests.

---

### Phase 4: Conflicts and Identity
- [ ] Implement Version-DAG branching and concurrent head detection (`resource_head.has_conflict = true`).
- [ ] Implement 3-tier merge rules:
  - `Observation` & `Encounter`: Immutable, append-only, corrections marked `entered-in-error`.
  - `AllergyIntolerance`: Set union for active; removals/refutations route to review.
  - `Patient` Demographics: Field-level LWW by HLC; identifiers merge as a set.
  - Clinically meaningful conflicts: Open a `conflict_case`.
- [ ] Implement Admin API to list and resolve conflict cases.
- [ ] Implement patient duplicate detection: Blocking (DOB + phonetic soundex) + Scoring (Jaro-Winkler).
- [ ] Implement patient merge and unmerge via `Patient.link` (fully reversible).
- **Acceptance Criteria:** Every merge rule has tests including concurrent edits from two clinics; clinically meaningful conflicts always open a case; a merge can be undone (Invariants 5, 6, 8).

---

### Phase 5: Security and Consent
- [ ] Implement encrypted H2 database at rest on edge using `CIPHER=AES` with key from environment variable/file.
- [ ] Implement Ed25519 batch signing and verification with node key registry.
- [ ] Implement edge node revocation mechanism in central `node_registry`.
- [ ] Configure TLS for local development.
- [ ] Implement Spring Security roles: `ROLE_CLINICIAN`, `ROLE_CLINIC_ADMIN`, `ROLE_CENTRAL_ADMIN`.
- [ ] Implement FHIR `Consent` enforcement in sync scope filtering and read operations.
- [ ] Implement `AuditEvent` logging for reads, writes, merges, and conflict resolutions.
- [ ] Document STRIDE-style threat model in `docs/ai/THREAT_MODEL.md` including physical lost-device limitations.
- **Acceptance Criteria:** Tampered, unsigned, and revoked-node batches are rejected; unauthorized access is denied; audit trail covers writes, merges, and resolutions (Invariant 7).

---

### Phase 6: Chaos and Verification
- [ ] Build in-process fault-injecting transport simulator in `sim` (network partitions, mid-sync disconnects, dropped/reordered packets, clock skew).
- [ ] Simulate N clinics operating concurrently with randomized reads, writes, and partitions.
- [ ] jqwik property-based tests verifying all 8 invariants across at least 1,000 randomized execution runs.
- [ ] Benchmark throughput, sync latency, and byte payload sizes; record in `docs/ai/BENCHMARKS.md`.
- **Acceptance Criteria:** All 8 invariants hold across at least 1,000 randomized runs; benchmark results recorded.

---

### Phase 7: Documentation and Release
- [ ] Write top-level `README.md` (problem statement, architecture diagram, quickstart, limitations, scope notes).
- [ ] Write scripted demo (`scripts/demo.sh` or documented steps): clinic goes offline, two clinics edit same patient, reconnect, conflict review, merge undo.
- [ ] Polish architecture documentation and add LICENSE.
- [ ] Write project retrospective in `docs/ai/DECISIONS.md` and prioritized future `BACKLOG.md`.
- [ ] Tag `v0.1.0`.
- **Acceptance Criteria:** A stranger can run the demo from the `README.md` alone.
