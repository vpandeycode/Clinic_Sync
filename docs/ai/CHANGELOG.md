# Changelog: clinic_sync

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased] - 2026-10-27

### Added
- **Phase 2: FHIR Data Layer**
  - Implemented RFC 9562 `UuidV7Generator` in `common`.
  - Implemented `FhirUtil` leveraging HAPI FHIR R4 with `StrictErrorHandler` parsing, validation, and history `Bundle` creation.
  - Implemented universal versioned store on both `edge-node` and `central-server`:
    - Full CRUD for `Patient`, `Encounter`, `Observation`, `AllergyIntolerance`.
    - Optimistic concurrency control via HTTP `If-Match` headers (HTTP 412 on stale version).
    - History endpoints (`GET /fhir/{resourceType}/{id}/_history`).
    - Soft-deletes via tombstones (`is_tombstone = true`, HTTP 410 on read).
    - Preserved complete version history with non-destructive version increments.
  - Built `SyntheticDataGenerator` in `sim` producing strictly valid synthetic FHIR R4 resources.
  - Added [docs/learning/phase-2.md](file:///home/hamlet/code/clinic_snyc/docs/learning/phase-2.md).
- **Phase 1: Foundation & Walking Skeleton**
  - Scaffolded 4-module Maven architecture: `common`, `edge-node`, `central-server`, `sim`.
  - Configured Java 21, Spring Boot 3.3.5, and Flyway database migrations with module-isolated schemas.
  - Implemented `docker-compose.yml` and `.github/workflows/ci.yml`.
  - Built `WalkingSkeletonIntegrationTest` proving end-to-end patient sync.
  - Added [docs/learning/phase-1.md](file:///home/hamlet/code/clinic_snyc/docs/learning/phase-1.md).
- **Phase 0: Docs and Decisions**
  - Initialized memory files in `docs/ai/` and ADRs 0001 through 0015.
  - Added [docs/learning/phase-0.md](file:///home/hamlet/code/clinic_snyc/docs/learning/phase-0.md).
