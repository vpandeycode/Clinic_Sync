# Sync Invariants: clinic_sync

> **Status:** Draft (Seed List)  
> **Last Updated:** 2026-10-27  

These invariants are the core safety guarantees of the system. Under any arbitrary sequence of network disconnections, message reorderings, duplicate deliveries, node crashes, and clock skews, the system must **never** violate these properties.

Every invariant listed here must have at least one automated test (unit, property-based using jqwik, integration, or chaos simulation) mapped to it before Phase 7 release.

---

## The Core Invariants

### 1. No Data Loss
- **Definition:** Any write acknowledged to an edge client must eventually reach the central server once connectivity is restored.
- **Violation Scenario:** Edge acknowledges a new `Observation` locally, crashes mid-sync, and drops the record from the transmission log.
- **Verification Strategy:** Integration test with mid-flight failure injection; Property-based tests verifying total count of acknowledged writes matches central database records post-convergence.

### 2. Convergence
- **Definition:** Once all nodes complete synchronization and no concurrent writes are underway, all edge nodes and the central server hold identical effective state for shared records.
- **Violation Scenario:** Node A and Node B receive updates in different order and settle on different final states.
- **Verification Strategy:** jqwik property-based tests simulating random write and sync topologies, asserting state equivalence across all replica stores.

### 3. Idempotency
- **Definition:** Applying the same sync batch, packet, or change entry multiple times produces the identical side-effect as applying it exactly once.
- **Violation Scenario:** Retried sync payload results in duplicate `Encounter` entries or duplicate observation records.
- **Verification Strategy:** Replay tests where identical payloads are posted repeatedly; database uniqueness and version checks.

### 4. Resumability
- **Definition:** A sync session interrupted at any arbitrary packet or byte offset can resume without re-transmitting already acknowledged changes and without skipping pending changes.
- **Violation Scenario:** Connection drops at 90% sync; restarting skips the remaining 10% or re-processes the first 90% causing corruption.
- **Verification Strategy:** Simulated socket disconnects during batch ingestion; verification of resume tokens / high-water marks.

### 5. History Preserved
- **Definition:** Prior versions of any FHIR resource are never destructively overwritten or purged. Every change creates an immutable version with explicit provenance.
- **Violation Scenario:** An update replaces an earlier record row in-place without preserving historical values or audit records.
- **Verification Strategy:** Invariant checks on FHIR `_history` endpoints and database append-only tables ensuring version counter monotonicity.

### 6. Safe Conflicts
- **Definition:** Any concurrent, conflicting modification that alters clinical meaning (e.g. conflicting medication doses, differing allergy declarations, contradictory diagnosis codes) is never resolved via silent Last-Write-Wins (LWW). It must be flagged for human review or preserved via safe non-destructive union.
- **Violation Scenario:** Clinic A records "Penicillin Allergy: Severe", Clinic B concurrently records "Penicillin Allergy: None"; system blindly keeps Clinic B based on wall-clock time.
- **Verification Strategy:** Automated conflict engine tests asserting that conflicting clinical assertions always route to the Review Queue and maintain dual visibility.

### 7. Integrity
- **Definition:** Any tampered, corrupted, truncated, or unsigned sync payload is rejected before ingestion into authoritative storage.
- **Violation Scenario:** Bit-flip in transit or unauthorized payload injection is parsed and partially committed.
- **Verification Strategy:** Payload signature verification tests, checksum assertions, and malformed payload fuzzing.

### 8. Reversibility
- **Definition:** Any administrative merge operation (e.g., merging two patient records identified as the same individual) must be reversible without loss of the original discrete records or their linked encounters.
- **Violation Scenario:** Merging Patient A into Patient B reassigns foreign keys destructively, making it impossible to separate them if the merge was mistaken.
- **Verification Strategy:** Merge/Unmerge integration test suite verifying that state before merge can be restored identically.
