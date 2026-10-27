# Sync Invariants: clinic_sync

> **Status:** Active / Accepted  
> **Last Updated:** 2026-10-27  

These 8 invariants are the core distributed safety guarantees of `clinic_sync`. Under any arbitrary sequence of network disconnections, message reorderings, duplicate deliveries, node crashes, and clock skews, the system must **never** violate these properties.

Every invariant listed here must have at least one automated test (unit test, jqwik property test, or integration test) passing before Phase 7 release.

---

## The 8 Core Invariants

### 1. No Data Loss
- **Formal Invariant:** Any write acknowledged to a client on an edge node must eventually reach the central server once connectivity is restored and sync completes.
- **Violation Scenario:** Edge acknowledges a triage `Observation` locally, crashes mid-sync, and drops the record from the transmission outbox or fails to retry.
- **Verification Strategy:**
  - Integration test with mid-flight connection drops.
  - Property-based tests verifying that the total count of distinct `(resource_type, resource_id, version_id)` triples acknowledged on all edge nodes equals the count present in Central's `resource_version` table post-convergence.

### 2. Convergence
- **Formal Invariant:** Once all edge nodes have completed synchronization with central and no active concurrent writes are taking place, all edge nodes and the central server hold identical effective state for all shared resources.
- **Violation Scenario:** Clinic Alpha and Clinic Beta receive updates in different order or at different times and settle on divergent final states.
- **Verification Strategy:**
  - jqwik property-based tests in `sim` generating arbitrary interleavings of concurrent edits and network partitions.
  - Assert that after full bidirectional sync, `computeStateChecksum()` across all nodes produces an identical hash.

### 3. Idempotency
- **Formal Invariant:** Applying the same sync batch, packet, or change entry multiple times has the identical side-effect as applying it exactly once: $f(f(x)) = f(x)$.
- **Violation Scenario:** Retried sync payload results in duplicate `Encounter` entries, duplicate version rows, or corrupt sequence counters.
- **Verification Strategy:**
  - Integration tests replaying identical push payloads 2x, 5x, and 10x against central endpoints.
  - Verify database constraints: `applied_changes` `UNIQUE(origin_node_id, origin_seq)` strictly enforces single execution and returns identical contiguous high-water marks.

### 4. Resumability
- **Formal Invariant:** A sync session interrupted at any arbitrary packet or byte boundary can resume upon reconnection without re-transmitting already acknowledged changes and without skipping pending changes.
- **Violation Scenario:** Connection drops at 90% sync; restarting either skips the remaining 10% (data loss) or re-processes the first 90% causing corruption.
- **Verification Strategy:**
  - Simulated socket disconnects during batch ingestion.
  - Verify edge `sync_state.last_pushed_seq` only advances up to the contiguous ACK returned by Central, resuming exactly at $seq + 1$.

### 5. History Preserved
- **Formal Invariant:** Prior versions of any FHIR resource are never destructively overwritten or purged. Every change produces an immutable version entry with verifiable provenance.
- **Violation Scenario:** An update mutates an existing record row in-place without retaining historical values, parent references, or author attribution.
- **Verification Strategy:**
  - Automated tests asserting that modifying a resource $N$ times results in exactly $N$ rows in `resource_version` with monotonically increasing version IDs and intact parent DAG pointers.
  - Verify FHIR `_history` endpoint returns the complete chronological version chain.

### 6. Safe Conflicts
- **Formal Invariant:** Any concurrent, conflicting modification that alters clinical meaning (e.g. conflicting allergy declarations, differing medication orders, contradictory diagnosis codes) is never resolved via silent Last-Write-Wins (LWW). It must be flagged for human review.
- **Violation Scenario:** Clinic Alpha records "Penicillin Allergy: Active (Severe)", Clinic Beta concurrently records "Penicillin Allergy: Refuted"; system blindly overwrites Clinic Alpha's entry based on wall-clock time.
- **Verification Strategy:**
  - Conflict engine tests asserting that competing clinical versions set `resource_head.has_conflict = true` and populate `conflict_case` with status `OPEN`.
  - Both conflicting clinical assertions remain visible to treating clinicians until administrative resolution.

### 7. Integrity
- **Formal Invariant:** Any tampered, corrupted, truncated, unsigned, or revoked-node sync payload is rejected before ingestion into authoritative storage.
- **Violation Scenario:** Bit-flip in transit, forged signature, or unauthorized payload injection from a revoked edge device is parsed and committed.
- **Verification Strategy:**
  - Security unit and integration tests injecting mutated JSON payloads, corrupted Ed25519 signatures, and revoked node IDs into `/api/v1/sync/push`.
  - Assert that all unauthorized payloads return `401 Unauthorized` or `400 Bad Request` and zero database changes are committed.

### 8. Reversibility
- **Formal Invariant:** Any administrative merge operation (e.g., merging duplicate patient records identified as the same person) must be completely reversible without loss of original discrete records or their linked encounters.
- **Violation Scenario:** Merging Patient A into Patient B reassigns foreign keys destructively, making it impossible to separate them if the merge was mistaken.
- **Verification Strategy:**
  - Merge/Unmerge integration test suite: Patient A and B are created with respective encounters; A is merged into B via `Patient.link`; unmerge is executed; assert that both A and B and their child encounters are restored to their pristine pre-merge states.
