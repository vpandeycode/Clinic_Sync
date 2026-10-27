# Learning Log: Phase 0 — Docs and Decisions

> **Phase:** 0 (Docs and Decisions)  
> **Date:** 2026-10-27  

---

## 1. What Was Built
In Phase 0, we established the complete technical, clinical, and architectural foundation for `clinic_sync`:
- **`PROJECT_BRIEF.md`:** Detailed problem definition, target user personas (clinicians, edge admins, central health officers), 6 realistic rural clinic failure scenarios, goals, non-goals, and measurable success metrics.
- **`ARCHITECTURE.md`:** Complete system topology with Mermaid diagrams, 4-module Maven layout, relational data model (`resource_version`, `resource_head`, edge `change_log`, central `applied_changes`), push-pull sync state machine, 3-tier clinical merge policy, identity deduplication, and Ed25519 cryptographic security.
- **`SYNC_INVARIANTS.md`:** Rigorous definitions and verification strategies for the 8 core distributed synchronization invariants.
- **`PHASES.md`:** 8-phase execution roadmap with acceptance criteria gates.
- **`DECISIONS.md`:** 15 comprehensive Architecture Decision Records (ADRs) capturing all fixed technical choices and system assumptions.

---

## 2. Why This Design?

### Why Hybrid Logical Clocks (HLC) over Physical Wall Clocks or Lamport Clocks?
Physical clocks (NTP) on edge hardware in rural settings are notoriously unreliable due to network partitions, dead CMOS batteries, or manual user resets. Conversely, pure Lamport logical clocks only track causal ordering ($e_1 \rightarrow e_2$) without any correlation to physical wall-clock time, making it impossible to determine whether an allergy was recorded 5 minutes ago or 3 years ago. HLC combines physical time with a logical monotonic counter, guaranteeing causality order while keeping timestamps bounded within $\epsilon$ of real physical time.

### Why Append-Only Version DAGs over Mutable In-Place Rows?
In clinical healthcare, destructive overwrites (`UPDATE patient SET ...`) are unacceptable because they erase history, provenance, and auditability. If two offline clinics edit the same patient concurrently, an in-place update forces a blind "last write wins" where one clinic's observation is wiped out. An append-only version table preserves every discrete event, forming a Directed Acyclic Graph (DAG) of versions that allows competing concurrent branches to be detected and resolved transparently.

### Why Hub-and-Spoke Push-Pull over Peer-to-Peer Mesh?
Real-world rural healthcare systems follow administrative hierarchies: health posts and rural clinics report to district hospitals, which report to regional health ministries. Hub-and-Spoke matches this domain model, eliminates the exponential complexity of distributed P2P consensus, and directly aligns with production architectures like **OpenMRS Sync 2.0**.

---

## 3. Key Concepts Involved
1. **Eventual Consistency & Replicated State:** Allowing nodes to accept writes locally while disconnected and converging to identical states upon reconciliation.
2. **Transactional Outbox Pattern:** Atomically writing business data and outbox sync records in the same local database transaction to prevent dual-write inconsistencies.
3. **Idempotent Consumers:** Designing sync endpoints where duplicate delivery of the same batch produces the exact same state without creating duplicate clinical entities ($f(f(x)) = f(x)$).
4. **Resumable High-Water Mark Cursors:** Tracking dense local sequences (`origin_seq`) and central timeline cursors (`global_seq`) to resume sync seamlessly after mid-flight network drops.
5. **Cryptographic Payload Integrity:** Using Ed25519 signatures over Canonical JSON to ensure payloads cannot be forged, tampered with, or replayed from revoked devices.

---

## 4. Study Recommendations & Official References

To master the concepts underpinning this phase, study these 4 resources:

1. **Designing Data-Intensive Applications (Martin Kleppmann)**
   - *Chapters to study:* Chapter 5 (Replication — Multi-Leader and Leaderless), Chapter 8 (The Trouble with Distributed Systems — Unreliable Clocks), and Chapter 9 (Consistency and Consensus).
2. **Logical Physical Clocks and Consistent Snapshots in Globally Distributed Databases (Kulkarni et al., 2014)**
   - *Official Paper:* Explains the mathematical derivation of Hybrid Logical Clocks (HLC), clock synchronization algorithms, and drift bounds.
3. **HL7 FHIR R4 Overview and Resource Data Model**
   - *Official Reference:* [HL7 FHIR R4 Documentation](https://hl7.org/fhir/R4/overview-dev.html)
   - *Specific sections:* Resource formatting, `_history` paradigm, and the `Bundle` resource type (`type = "transaction"` vs `"batch"`).
4. **OpenMRS Sync 2.0 Architecture Documentation**
   - *Official Reference:* [OpenMRS Sync 2.0 Wiki](https://wiki.openmrs.org/display/projects/Sync+2.0)
   - *Specific concepts:* How OpenMRS uses push/pull feeds, event tracking (`SyncMessage`), and parent-child server synchronization.
