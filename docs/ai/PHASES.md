# Project Roadmap & Phases: clinic_sync

> **Status:** Phase 0 Active  
> **Last Updated:** 2026-10-27  

Each phase has strict exit criteria. A phase cannot be marked complete until all checkboxes are checked, all verification tests pass, and the user explicitly approves advancing.

---

### Phase 0: Discovery & Scope (Week 1)
- [ ] Ask focused discovery questions (skill level, daily hours, critical failure scenarios).
- [ ] Complete `PROJECT_BRIEF.md` with explicit problem definition, goals, non-goals, user personas.
- [ ] Draft and refine `SYNC_INVARIANTS.md`.
- [ ] Identify candidate GSoC study links (OpenMRS Sync 2.0, DHIS2, Apache projects).
- **Exit Criteria:** `PROJECT_BRIEF.md` and `SYNC_INVARIANTS.md` reviewed and approved by user.

---

### Phase 1: Architecture & Design (Weeks 1 to 2)
- [ ] Propose and evaluate Clock Strategy (Lamport vs. Version Vectors vs. Hybrid Logical Clocks).
- [ ] Propose and evaluate Edge Storage Engine (SQLite with SQLCipher vs Encrypted H2).
- [ ] Propose and evaluate Sync Topology and Delta Protocol (Push-Pull, Outbox pattern, batching).
- [ ] Propose and evaluate Merge Strategy & Conflict Review Queue design.
- [ ] Document Architecture Decision Records (ADRs) in `DECISIONS.md`.
- [ ] Define FHIR data models, schema, and API contracts.
- **Exit Criteria:** `ARCHITECTURE.md` and all Phase 1 ADRs approved by user.

---

### Phase 2: Foundation (Weeks 2 to 3)
- [ ] Initialize Maven multi-module structure (`clinic-sync-common`, `clinic-sync-edge`, `clinic-sync-central`, `clinic-sync-sim`).
- [ ] Configure Java 21, Spring Boot 3.x, code formatting (Spotless / Checkstyle), CI workflow (GitHub Actions).
- [ ] Setup Docker Compose with PostgreSQL for central server.
- [ ] Setup local embedded storage harness for edge.
- [ ] Configure Testcontainers and jqwik test dependencies.
- [ ] Implement walking skeleton: A basic `Patient` created on Edge reaches Central over sync endpoint.
- **Exit Criteria:** Walking skeleton runs end-to-end; unit and integration tests pass in local build and CI.

---

### Phase 3: FHIR Data Layer (Weeks 3 to 4)
- [ ] Integrate HAPI FHIR core library (R4).
- [ ] Implement `Patient`, `Encounter`, `Observation`, `AllergyIntolerance` persistence.
- [ ] Implement immutable resource versioning, history tracking, and tombstone deletion.
- [ ] Add FHIR validation rules and audit logging (`AuditEvent`).
- **Exit Criteria:** Versioned FHIR CRUD and `_history` work reliably on both edge and central nodes.

---

### Phase 4: Sync Core (Weeks 5 to 7)
- [ ] Implement chosen distributed clock in `clinic-sync-common` with comprehensive property tests.
- [ ] Implement Edge Outbox / Change Journal tracking resource changes.
- [ ] Implement push-then-pull delta synchronization protocol.
- [ ] Implement batching, idempotency tokens, and resumable sync cursors/high-water marks.
- [ ] Implement selective sync (clinic-scoped partitioning).
- **Exit Criteria:** Two-way sync converges under intermittent connectivity; Invariants 1-5 verified with tests.

---

### Phase 5: Conflicts & Identity (Weeks 8 to 9)
- [ ] Implement field-level merge strategies (e.g., Demographics LWW with audit; Allergies safe union).
- [ ] Implement human-in-the-loop Conflict Review Queue for conflicting clinical edits.
- [ ] Implement duplicate patient detection (probabilistic or deterministic identifier matching).
- [ ] Implement reversible patient record merge (`Patient/$merge` and unmerge capabilities).
- **Exit Criteria:** Conflict scenarios resolved per documented rules; merges fully reversible; Invariants 6 and 8 verified.

---

### Phase 6: Security & Consent (Week 10)
- [ ] Implement edge database encryption at rest.
- [ ] Implement TLS communication with mutual authentication or signed sync payloads (HMAC/Ed25519).
- [ ] Implement basic role-based access control (Clinician vs Admin).
- [ ] Integrate FHIR `Consent` enforcement and comprehensive `AuditEvent` generation.
- [ ] Document lost/stolen edge device security posture and threat model.
- **Exit Criteria:** Threat model documented; security and encryption tests pass; Invariant 7 verified.

---

### Phase 7: Chaos & Verification (Week 11)
- [ ] Build simulation harness in `clinic-sync-sim` (network partitions, latency, dropped packets, mid-sync disconnects).
- [ ] Simulate extreme clock skew (past, future, drifting edge clocks).
- [ ] Run jqwik property-based tests verifying convergence across randomized concurrent edits.
- [ ] Collect performance metrics (sync latency, payload sizes, database overhead).
- **Exit Criteria:** Zero data loss and complete convergence proven under chaos test suite; Invariants 1-8 verified.

---

### Phase 8: Documentation & Release (Week 12)
- [ ] Write comprehensive top-level `README.md` (overview, quickstart, architecture, demo, limitations).
- [ ] Polish all technical documentation in `/docs/`.
- [ ] Create interactive demo script / walkthrough video.
- [ ] Document retrospective in `DECISIONS.md` and outline future backlog (P2P sync, mobile edge client).
- [ ] Tag v1.0.0 release.
- **Exit Criteria:** Clean repo state; external developer can run the demo from `README.md` in under 10 minutes.
