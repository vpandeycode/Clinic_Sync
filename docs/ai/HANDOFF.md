# Handoff: clinic_sync

> **Current Date:** 2026-10-27  
> **Active Phase:** Phase 2 Completed -> Transitioning to Phase 3: Sync Core  
> **Status:** Versioned FHIR data layer fully operational on edge and central; `mvn verify` passes with 0 errors.  

---

## 1. Current State
- Operating Mode: **BUILD (Autonomous)**.
- **Phase 0:** Completed and committed.
- **Phase 1:** Completed and committed.
- **Phase 2:** Completed and verified:
  - RFC 9562 UUIDv7 generator in `common`.
  - HAPI FHIR R4 parser/validator with strict error handling.
  - Complete versioned CRUD on both `edge-node` and `central-server` for `Patient`, `Encounter`, `Observation`, `AllergyIntolerance`.
  - Optimistic concurrency control via `If-Match`.
  - Full history bundle generation via `/{resourceType}/{id}/_history`.
  - Soft deletion / tombstones (returning HTTP 410 on read).
  - Synthetic data generator in `sim`.
  - All tests passing in `common`, `edge-node`, `central-server`, `sim`.

---

## 2. In-Progress Work
- Committing Phase 2 deliverables to git.
- Transitioning to **Phase 3: Sync Core**:
  - Hybrid Logical Clock (HLC) in `common` (physical ms + counter + node id).
  - jqwik property-based tests for HLC monotonicity and future drift bounds.
  - Canonical JSON serializer and Ed25519 payload signing helpers in `common`.
  - Central push endpoint with contiguous ACK calculation and future drift rejection.
  - Central pull endpoint with `global_seq` cursor, paging, and clinic scope filtering.
  - Edge sync client with push-then-pull, exponential backoff, and jitter.
  - Automated tests for Invariants 1–4 (No data loss, Convergence, Idempotency, Resumability).

---

## 3. Next 3 Steps
1. Commit Phase 2 changes to git.
2. Implement HLC and Ed25519 canonical JSON signing in `common` with property tests.
3. Implement Central pull endpoint and complete the bidirectional Push-then-Pull sync engine.

---

## 4. Open Questions & Blockers
- None. Proceeding autonomously into Phase 3.
