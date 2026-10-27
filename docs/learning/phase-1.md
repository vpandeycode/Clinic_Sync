# Learning Log: Phase 1 — Foundation & Walking Skeleton

> **Phase:** 1 (Foundation and Walking Skeleton)  
> **Date:** 2026-10-27  

---

## 1. What Was Built
- **Multi-Module Maven Architecture:** Configured root POM and 4 decoupled modules:
  - `common`: Framework-free core synchronization models (`SyncBatch`, `ChangeRecord`, `SyncAck`).
  - `edge-node`: Spring Boot edge application with H2 database (AES-256 encrypted at rest via `CIPHER=AES`), Flyway migrations, local patient REST endpoints, and `EdgeSyncClient`.
  - `central-server`: Spring Boot central application with PostgreSQL/H2 relational schema, Flyway migrations, idempotent sync ingestion endpoint (`/api/v1/sync/push`), and central FHIR patient endpoint (`/fhir/Patient/{id}`).
  - `sim`: Simulation and integration test harness.
- **Docker Compose Topology:** Multi-container configuration (`docker-compose.yml`) orchestrating Central PostgreSQL, Central Server, and two distinct Edge Nodes (Clinic Alpha and Clinic Beta).
- **CI Pipeline:** Automated build and test verification workflow configured in `.github/workflows/ci.yml`.
- **End-to-End Walking Skeleton:** Implemented and automated `WalkingSkeletonIntegrationTest`:
  - Patient created on Edge Node A.
  - Edge Node stores record in `resource_version`, updates `resource_head`, and logs to `change_log`.
  - Edge sync client pushes change batch to Central Server.
  - Central validates, dedupes, persists to `resource_version`/`resource_head`/`applied_changes`, and returns contiguous ACK.
  - Edge advances cursor (`last_pushed_seq`).
  - Central serves identical record via `/fhir/Patient/{id}`.
  - Verified idempotency: re-pushing batch produces zero duplicate entries and returns status `UP_TO_DATE`.

---

## 2. Why This Design?

### Why Separate Flyway Migration Directories Per Module?
When multiple modules in a Maven reactor share the same classpath in integration test modules (like `sim`), default Flyway scanning (`classpath:db/migration`) discovers all migration scripts from all JARs. If multiple modules have `V1__init_...sql`, Flyway throws a collision exception. Segregating locations into `classpath:db/migration/edge` and `classpath:db/migration/central` guarantees absolute isolation while maintaining clean modular boundaries.

### Why Portable UPDATE-then-INSERT over Database-Specific MERGE/ON CONFLICT?
Different SQL dialects have subtle syntactic differences: PostgreSQL uses `ON CONFLICT (cols) DO UPDATE SET`, H2 historically used `MERGE INTO ... KEY (...)`, and MySQL uses `ON DUPLICATE KEY UPDATE`. An explicit `UPDATE ... WHERE ...` that falls back to `INSERT INTO ...` when zero rows are updated is ANSI-SQL standard and 100% portable across PostgreSQL, H2, SQLite, and MySQL without proprietary vendor dialect locks.

---

## 3. Key Concepts Involved
1. **Walking Skeleton:** Alistair Cockburn's concept of an end-to-end implementation of the thinnest possible slice of business logic across all architectural tiers, proving connectivity and tooling early.
2. **Transactional Outbox in Spring JDBC:** Recording business mutations (`resource_version`) and transmission queue entries (`change_log`) in the same physical database transaction using Spring's `JdbcClient`.
3. **Idempotent Ingestion via Unique Constraint:** Using `applied_changes(origin_node_id, origin_seq UNIQUE)` to guarantee that repeated network retries of the same batch never result in duplicate records.

---

## 4. Study Recommendations & Official References

1. **Spring Framework 6 Reference — JdbcClient and JdbcTemplate**
   - *Official Reference:* [Spring Framework Data Access Documentation](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html#jdbc-JdbcClient)
   - *Focus:* Fluent querying, `KeyHolder` for auto-generated identity keys, and transaction management via `@Transactional`.
2. **Flyway Database Migration Concepts & Multi-Module Configuration**
   - *Official Reference:* [Flyway Official Documentation](https://documentation.red-gate.com/fd/migrations-184127470.html)
   - *Focus:* Migration naming conventions, schema history table lifecycle, and classpath location configuration.
3. **The Walking Skeleton Architectural Pattern (Alistair Cockburn)**
   - *Official Reference:* *Growing Object-Oriented Software, Guided by Tests* (Steve Freeman & Nat Pryce, Chapter 4: Building a Walking Skeleton).
4. **H2 Database File Encryption Engine**
   - *Official Reference:* [H2 Database Features: File Encryption](https://www.h2database.com/html/features.html#file_encryption)
   - *Focus:* Using `CIPHER=AES` in JDBC URLs and password separation (`<file_password> <user_password>`).
