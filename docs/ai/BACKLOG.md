# Backlog: clinic_sync

> **Priority Legend:** P0 = Critical Path, P1 = Important, P2 = Nice to Have, P3 = Deferred / Stretch

---

## Current Sprint / Phase 0: Discovery & Scope
- [ ] [P0] Collect and incorporate user answers to Phase 0 discovery questions into `PROJECT_BRIEF.md`.
- [ ] [P0] Refine `SYNC_INVARIANTS.md` with explicit testing boundaries.
- [ ] [P0] Formulate Phase 1 design questions and options (ADRs).
- [ ] [P1] Map out relevant OpenMRS Sync 2.0 / FHIR module reference repositories.

---

## Phase 1: Architecture & Design
- [ ] [P0] Write ADR: Edge storage selection (SQLite/SQLCipher vs Encrypted H2).
- [ ] [P0] Write ADR: Distributed clock strategy (Lamport vs Version Vector vs HLC).
- [ ] [P0] Write ADR: Sync protocol and journal format (Delta push-pull vs Event sourcing vs Outbox).
- [ ] [P0] Write ADR: Clinical conflict resolution and human review queue architecture.
- [ ] [P1] Design API payload contracts for sync exchange.

---

## Phase 2: Foundation & Skeleton
- [ ] [P0] Scaffold multi-module Maven project (`common`, `edge-node`, `central-server`, `sim`).
- [ ] [P0] Set up Docker Compose for PostgreSQL.
- [ ] [P0] Set up basic Spring Boot web application for central and edge.
- [ ] [P0] Implement end-to-end walking skeleton (Edge creates patient -> sends to Central).

---

## Tech Debt & Improvements
- [ ] Set up pre-commit formatting checks (Spotless).
- [ ] Set up CI with GitHub Actions.
