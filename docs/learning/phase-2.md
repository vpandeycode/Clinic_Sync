# Learning Log: Phase 2 — FHIR Data Layer

> **Phase:** 2 (FHIR Data Layer)  
> **Date:** 2026-10-27  

---

## 1. What Was Built
- **UUIDv7 Generator:** Implemented RFC 9562 compliant `UuidV7Generator` in `common`: 48-bit timestamp ms prefix, monotonic sequence counter, 128-bit collision-free decentralized generation preserving database B-Tree index locality.
- **HAPI FHIR R4 Library Integration:** Integrated HAPI FHIR R4 into `common` with `FhirUtil`: JSON serialization, StrictErrorHandler parsing, validation, and official FHIR `_history` `Bundle` generation.
- **Universal Versioned CRUD Engine:** Implemented append-only version storage on both `edge-node` and `central-server`:
  - `create`: Validates JSON, generates UUIDv7 ID if missing, sets versionId "1", writes to `resource_version` and `resource_head` (plus `change_log` on edge).
  - `read`: Reads active head version; returns HTTP 410 Gone if tombstoned.
  - `update`: Enforces optimistic concurrency via HTTP `If-Match: "{versionId}"` (returns HTTP 412 on conflict); increments version counter monotonically without overwriting historical rows.
  - `_history`: Assembles a complete chronological FHIR `Bundle` (`type = history`) representing the resource's entire lifecycle.
  - `delete`: Soft-deletes the resource by creating a tombstone version (`is_tombstone = true`) with parent pointer, ensuring Invariant 5 (History Preserved) is never violated.
- **Support for Core Outpatient Resources:** `Patient`, `Encounter`, `Observation`, `AllergyIntolerance`, `Consent`, and `AuditEvent`.
- **Synthetic Data Generator in `sim`:** `SyntheticDataGenerator` producing strictly synthetic FHIR R4 resources with standard LOINC and SNOMED codes.
- **Automated Verification:** Verified in `EdgeFhirDataLayerTest`, `CentralFhirDataLayerTest`, `SyntheticDataGeneratorTest`, and `UuidV7GeneratorTest`.

---

## 2. Why This Design?

### Why Strict Parser Error Handling over Heavy XSD Schemas?
HAPI FHIR's default XML-based `SchemaBaseValidator` attempts to resolve external `.xsd` schemas on classpath, incurring significant memory overhead (~50MB+) and slow parse latencies. Configuring HAPI's parser with `StrictErrorHandler` validates all unknown elements, invalid primitive types, and structural violations in-memory in microseconds without classpath schema bloat.

### Why HTTP ETags & If-Match for Optimistic Concurrency?
In an offline-first distributed system, two clinicians or processes can view the same record concurrently. Without optimistic locking, Clinic Worker B might overwrite Clinic Worker A's changes without realizing A updated the record. Using the standard HTTP `If-Match` header guarantees that mutations only apply if the caller's known version matches the active database head, preventing lost updates.

---

## 3. Key Concepts Involved
1. **RFC 9562 UUIDv7 Monotonicity:** Standardizing on time-ordered UUIDs to achieve high insertion throughput in relational B-Trees while remaining decentralized.
2. **FHIR R4 `_history` Semantics:** Understanding how FHIR represents historical resource revisions using `Bundle.type = "history"` with entry HTTP verbs (`POST`, `PUT`, `DELETE`).
3. **Optimistic Concurrency Control (OCC):** Detecting concurrent updates at commit time rather than locking database rows.
4. **Soft Deletion & Tombstones:** Ensuring clinical data provenance is permanent; deletions are auditable historical events.

---

## 4. Study Recommendations & Official References

1. **RFC 9562: Universally Unique IDentifiers (UUID)**
   - *Official Reference:* [IETF RFC 9562](https://datatracker.ietf.org/doc/rfc9562/)
   - *Sections:* Section 5.7 (UUIDv7 Format and Generation Rules).
2. **HL7 FHIR R4 RESTful API — History Interaction**
   - *Official Reference:* [HL7 FHIR REST API Specification](https://hl7.org/fhir/R4/http.html#history)
   - *Focus:* History bundle format, `_history` parameter syntax, and ETag header management.
3. **HAPI FHIR Parsing and Serializing Guide**
   - *Official Reference:* [HAPI FHIR Parsers Documentation](https://hapifhir.io/hapi-fhir/docs/model/parsers.html)
   - *Focus:* `IParser`, `StrictErrorHandler`, and custom extensions.
4. **Martin Fowler: Patterns of Enterprise Application Architecture — Optimistic Offline Lock**
   - *Official Reference:* [Martin Fowler Catalog: Optimistic Offline Lock](https://martinfowler.com/eaaCatalog/optimisticOfflineLock.html).
