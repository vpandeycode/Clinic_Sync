# Changelog: clinic_sync

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased] - 2026-10-27

### Added
- Completed Phase 0 discovery inputs:
  - Developer experience profile (Java 21, Spring Boot DI; FHIR & Distributed Systems study track).
  - Scope defined: Outpatient Triage & Vitals (`Patient`, `Encounter`, `Observation`, `AllergyIntolerance`).
  - Target open-source health community aligned: **OpenMRS**.
  - Edge database selected: **H2 with AES encryption at rest** (`CIPHER=AES`).
  - Edge execution topology: Dual-mode Spring Boot (standalone JAR + Docker Compose simulation).
  - Prioritized failure scenarios: Concurrent conflicting edits, mid-sync drops, extended offline isolation.
- Refined [PROJECT_BRIEF.md](file:///home/hamlet/code/clinic_snyc/docs/ai/PROJECT_BRIEF.md) and [SYNC_INVARIANTS.md](file:///home/hamlet/code/clinic_snyc/docs/ai/SYNC_INVARIANTS.md).
- Initialized all project memory files in `docs/ai/`.
