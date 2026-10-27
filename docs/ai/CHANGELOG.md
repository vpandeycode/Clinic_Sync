# Changelog: clinic_sync

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased] - 2026-10-27

### Added
- **Phase 1: Foundation & Walking Skeleton**
  - Scaffolded 4-module Maven architecture: `common`, `edge-node`, `central-server`, `sim`.
  - Configured Java 21, Spring Boot 3.3.5, and Flyway database migrations with module-isolated schemas.
  - Implemented `docker-compose.yml` for multi-node local orchestration (PostgreSQL + Central + 2 Edge Nodes).
  - Implemented `.github/workflows/ci.yml` pipeline.
  - Implemented Edge Node storage repository and sync client with H2 file encryption support.
  - Implemented Central Server idempotent push endpoint and FHIR query API.
  - Built `WalkingSkeletonIntegrationTest` proving end-to-end flow: Edge creates Patient -> pushes batch to Central -> Central records and serves Patient -> Idempotent re-push verified.
  - Added [docs/learning/phase-1.md](file:///home/hamlet/code/clinic_snyc/docs/learning/phase-1.md).
- **Phase 0: Docs and Decisions**
  - Initialized memory files in `docs/ai/` and ADRs 0001 through 0015.
  - Added [docs/learning/phase-0.md](file:///home/hamlet/code/clinic_snyc/docs/learning/phase-0.md).
