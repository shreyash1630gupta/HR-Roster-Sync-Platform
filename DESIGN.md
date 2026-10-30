# 📐 MoveInSync HR Roster Sync — System Architecture & Design Blueprint
  
---

## 1. Problem Statement & Main Components

Enterprise clients of MoveInSync (e.g., Google, Infosys) maintain master employee rosters in external HR systems (SAP, Workday) and synchronize changes via real-time webhooks and nightly CSV exports (up to 200,000 rows). Real-world data is chaotic: events duplicate, arrive out-of-order, contain malformed records, and exports can be truncated. Furthermore, HR fields (`name`, `email`, `office_code`) and Transport Admin fields (`pickup_point`, `special_assistance_needs`) have strict authority boundaries.

### Main System Components
* **`JwtAuthenticationFilter` & `SecurityConfig`:** Stateless RFC 7519 HMAC-SHA256 authentication, tenant context extraction into `TenantContextHolder`, and RBAC enforcement.
* **`SyncEventController` & `SyncEventService`:** Webhook entry point with 1,000-event batch ceiling, boundary validation, and per-record status isolation.
* **`EventValidator` & `OfficeCacheService`:** Event-type specific validator beans with an in-memory Caffeine cache (5-min TTL) for tenant office validation.
* **`SyncEngine`:** Core forward-progress processor enforcing 2-level idempotency, monotonic ordering, SHA-256 conflict detection, and field ownership protection.
* **`FieldOwnershipService`:** Field authorization firewall ensuring HR updates never overwrite Transport Admin commute attributes.
* **`ReconciliationService` & `StagingTableManager`:** Bounded-memory streaming CSV parser loading temporary PostgreSQL unlogged staging tables for set-based SQL diffing.
* **`SafetyBrakeCalculator`:** Circuit breaker halting mass deactivations when missing employee ratio $\ge 30\%$.
* **`OutboxPoller` & `EventPublisher`:** Scheduled background daemon (500ms) claiming unpublished outbox events using `SELECT ... FOR UPDATE SKIP LOCKED` with exponential backoff.
* **`DownstreamSideEffectHandler`:** Consumer cancelling future active bookings (`pickup_at > NOW()`) and freeing vehicle seats with consumer idempotency logging.

---

## 2. High-Level Architecture Diagram

```mermaid
flowchart TD
    subgraph Clients["External Callers & Upstream Systems"]
        HR[Enterprise HR System]
        TA[Transport Admin UI]
        OPS[Operations Dashboard]
        PROM[Prometheus Scraper]
    end

    subgraph Security["Security & Context Boundary"]
        SEC[JwtAuthenticationFilter + SecurityConfig]
        TCH[TenantContextHolder ThreadLocal]
        MDC[SLF4J MDC Correlation ID]
    end

    subgraph Ingestion["Dual-Path Ingestion Engine"]
        REST[SyncEventController / Batch Ingestion]
        RECON[ReconciliationController / Streaming CSV Diff]
    end

    subgraph CoreEngine["Core Processing & Domain Logic"]
        VAL[EventValidator + OfficeCacheService]
        ENG[SyncEngine Forward-Progress Processor]
        REC_ENG[ReconciliationService Set-Based Diff]
        FOS[FieldOwnershipService]
    end

    subgraph Storage["PostgreSQL 15 Multi-Tenant Database"]
        DB_EMP[(employees)]
        DB_PRO[(processed_events / Level 1 & 2)]
        DB_STG[(unlogged recon_staging)]
        DB_OUT[(outbox_events)]
        DB_BKG[(bookings & seat_assignments)]
        DB_POUT[(processed_outbox_events)]
    end

    subgraph Async["Asynchronous Daemons & Side-Effects"]
        POL[OutboxPoller SKIP LOCKED Daemon]
        DISP[EventPublisher Dispatcher]
        CONS[DownstreamSideEffectHandler]
    end

    HR -->|Real-time Events| SEC
    HR -->|Nightly CSV| SEC
    TA -->|Transport Updates| SEC
    OPS -->|Brake Approval & DLQ| SEC
    PROM -->|/actuator/prometheus| SEC

    SEC --> TCH
    SEC --> MDC
    SEC --> REST
    SEC --> RECON

    REST --> VAL
    VAL -->|Cached Office Check| CoreEngine
    REST --> ENG
    ENG -->|Mask Transport Fields| FOS
    ENG -->|Atomic Write| DB_EMP
    ENG -->|Idempotency Log| DB_PRO
    ENG -->|Outbox Pattern| DB_OUT

    RECON --> REC_ENG
    REC_ENG -->|Stream COPY| DB_STG
    REC_ENG -->|SQL Set Diff Join| DB_EMP

    POL -->|SELECT ... FOR UPDATE SKIP LOCKED| DB_OUT
    POL --> DISP
    DISP --> CONS
    CONS -->|Cancel Future Bookings & Free Seats| DB_BKG
    CONS -->|Consumer Idempotency Log| DB_POUT
```

---

## 3. Core Relational Data Model

All domain tables enforce strict multi-tenancy via composite primary keys and tenant-scoped unique constraints:

| Entity / Table | Primary Key | Natural / Unique Business Keys | Core Columns & Purpose |
|---|---|---|---|
| `tenants` | `id` (UUID) | `name` | Enterprise client account |
| `offices` | `id` (UUID) | `UNIQUE (tenant_id, code)` | Valid corporate office locations |
| `employees` | `id` (UUID) | `UNIQUE (tenant_id, external_employee_id)`<br>`UNIQUE (tenant_id, id)` | Master roster. Stores HR attributes, Transport attributes, `current_version`, `last_payload_hash`, `status`, and JPA `@Version` |
| `processed_events` | `id` (UUID) | `UNIQUE (event_id)` *(Level 1)*<br>`UNIQUE (tenant_id, external_employee_id, source_version)` *(Level 2)* | Idempotency & ordering ledger. Stores payload hash, execution status, and cached response payload |
| `outbox_events` | `id` (UUID) | Index: `(status, next_attempt_at)` | Transactional outbox table. Stores `payload`, `retry_count`, `next_attempt_at`, and `status` (`PENDING`, `PUBLISHED`, `DEAD_LETTER`) |
| `processed_outbox_events`| `outbox_event_id` | `PRIMARY KEY (outbox_event_id)` | Downstream consumer idempotency log |
| `bookings` | `id` (UUID) | Index: `(tenant_id, employee_id, pickup_at)` | Daily commute cab reservations (`ACTIVE`, `CANCELLED_EMPLOYEE_EXIT`) |
| `seat_assignments` | `id` (UUID) | Index: `(tenant_id, employee_id, status)` | Reserved vehicle seats (`ASSIGNED`, `FREED`) |
| `reconciliation_runs` | `id` (UUID) | `UNIQUE (tenant_id, file_checksum)` | Nightly sync run audit records, counts, checksum, and status |
| `reconciliation_candidates`| `id` (UUID) | Index: `(run_id, status)` | Missing employee IDs quarantined when safety brake trips |

---

## 4. API Surface & Contract Overview

| Method | Endpoint Path | Allowed Roles | Request / Response Behavior |
|:---:|---|:---:|---|
| `POST` | `/api/v1/auth/token` | Public | Generates signed HMAC-SHA256 JWT for tenant and specified role |
| `POST` | `/api/v1/sync/events` | `HR_SYSTEM`, `ADMIN` | Ingests webhook batch ($\le 1,000$). Returns per-record statuses (`ACCEPTED`, `SKIPPED`, `CONFLICT`, `REJECTED`) |
| `POST` | `/api/v1/sync/reconcile` | `HR_SYSTEM`, `ADMIN` | Multipart CSV bulk reconciliation upload. Returns run summary or `NEEDS_APPROVAL` |
| `GET` | `/api/v1/employees/{id}` | `TRANSPORT_ADMIN`, `OPERATOR`, `ADMIN` | Fetches segregated HR identity and Transport commute fields |
| `PUT` | `/api/v1/employees/{id}/transport-fields` | `TRANSPORT_ADMIN`, `ADMIN` | Updates transport-owned commute attributes (`pickup_point`, `special_needs`). Rejects HR fields with 403 |
| `POST` | `/api/v1/reconciliation/{id}/approve` | `OPERATOR`, `ADMIN` | Operator overrides safety brake; applies quarantined deactivations |
| `POST` | `/api/v1/reconciliation/{id}/reject` | `OPERATOR`, `ADMIN` | Aborts reconciliation run without altering employee records |
| `GET` | `/api/v1/outbox/dead-letter` | `OPERATOR`, `ADMIN` | Inspects failed events in `DEAD_LETTER` state |
| `POST` | `/api/v1/outbox/{id}/replay` | `OPERATOR`, `ADMIN` | Resets attempt count to 0 and re-queues event for poller retry |
| `GET` | `/actuator/health` | Public | Liveness and readiness status probes |
| `GET` | `/actuator/prometheus` | `OPERATOR`, `ADMIN` | Scrapes Micrometer operational metrics |

---

## 5. Sequence Diagram for the Hardest Flow: Nightly Reconciliation Engine

The most complex distributed workflow is the **Nightly Reconciliation Engine**, which orchestrates streaming I/O, database staging, set-based diffing, temporal watermark guards, safety brake circuit breaking, and human-in-the-loop approvals:

```mermaid
sequenceDiagram
    autonumber
    actor HR as Upstream HR System
    actor OP as Human Operations Admin
    participant Filter as JwtAuthenticationFilter
    participant Recon as ReconciliationService
    participant Lock as TenantAdvisoryLockService
    participant Stage as StagingTableManager
    participant DB as PostgreSQL 15 Master
    participant Brake as SafetyBrakeCalculator
    participant Outbox as OutboxPoller & Downstream

    HR->>Filter: POST /api/v1/sync/reconcile (CSV File, generated_at)
    Filter->>Filter: Validate JWT & Tenant Match
    Filter->>Recon: Stream Multipart CSV

    Recon->>Lock: pg_try_advisory_lock(tenant_id)
    alt Lock Acquisition Failed (Concurrent Run Active)
        Lock-->>Recon: false
        Recon-->>HR: HTTP 409 Conflict ("Reconciliation already in progress")
    else Lock Acquired
        Recon->>DB: Check file_checksum in reconciliation_runs
        alt Duplicate File Detected
            Recon-->>HR: HTTP 409 Conflict ("Export file already processed")
        else Unique File
            Recon->>Stage: Create Temporary UNLOGGED staging table
            loop Stream File in Chunks (1,000 rows, O(1) Heap Memory)
                Stage->>Stage: Validate Row Format & Compute SHA-256
                Stage->>DB: Batch INSERT into staging table
            end

            critical Database Set-Based Relational Diff
                Recon->>DB: Full Outer Join (staging vs. employees)
                DB-->>Recon: Counts: NEW, CHANGED, MISSING
            end

            note over Recon,DB: Temporal Watermark Guard: If db.updated_at >= export.generated_at, skip deactivation

            Recon->>Brake: Evaluate (missing_count / total_active)
            alt Missing Ratio >= 0.30 (Safety Brake Tripped)
                Brake-->>Recon: SAFETY_BRAKE_TRIGGERED
                Recon->>DB: Save Candidates to reconciliation_candidates table
                Recon->>DB: Update run status = NEEDS_APPROVAL
                Recon->>Lock: Release Advisory Lock
                Recon-->>HR: HTTP 202 Accepted (Status: NEEDS_APPROVAL, Brake Active)

                note over OP,Recon: Human-in-the-Loop Operator Intervention
                OP->>Filter: GET /api/v1/reconciliation/{id}
                Filter-->>OP: Run Details (Missing Ratio: 42%, Candidates: 1,200)
                OP->>Filter: POST /api/v1/reconciliation/{id}/approve
                Filter->>Recon: Execute Approved Deactivations
                critical Apply Approved Run
                    Recon->>DB: UPDATE employees SET status = 'INACTIVE'
                    Recon->>DB: INSERT outbox_events (EMPLOYEE_DEACTIVATED)
                    Recon->>DB: Update run status = COMPLETED
                end
                Recon-->>OP: HTTP 200 OK ("Reconciliation run approved and applied")
            else Normal Run (Missing Ratio < 0.30)
                critical Apply Safe Reconciliation
                    Recon->>DB: INSERT NEW employees
                    Recon->>DB: UPDATE CHANGED employees (Respect Field Ownership)
                    Recon->>DB: DEACTIVATE MISSING employees
                    Recon->>DB: INSERT outbox_events
                    Recon->>DB: Update run status = COMPLETED
                end
                Recon->>Lock: Release Advisory Lock
                Recon-->>HR: HTTP 200 OK (Run COMPLETED)
            end

            opt Asynchronous Downstream Processing
                Outbox->>DB: Poll outbox_events (FOR UPDATE SKIP LOCKED)
                Outbox->>DB: Cancel future bookings (pickup_at > NOW()) & Free seats
            end
        end
    end
```

---

## 6. Key Trade-Offs & Architectural Decisions

| Decision | Alternative Considered | Chosen Approach & Rationale |
|---|---|---|
| **Diff Location** | In-Memory Java HashMap Diff | **SQL Staging Table Joins:** Loading 200K entities into Java memory consumes ~180MB heap and triggers heavy GC pauses. SQL joins execute in <0.5s in PostgreSQL engine without touching JVM memory ($O(1)$ RAM). |
| **Outbox Claiming** | Optimistic Locking (`version`) | **`SELECT FOR UPDATE SKIP LOCKED`:** Optimistic locking fails under high worker concurrency due to rollback retries. `SKIP LOCKED` allows concurrent workers to claim disjoint partitions seamlessly. |
| **Office Validation** | Direct DB queries on every row | **Tenant-Scoped Caffeine Cache:** Office validation is on the critical write path for 1,000-event batches. 5-min TTL cache provides 99.8% hit rate with explicit eviction on office deactivations. |
| **Idempotency** | Redis distributed locks | **PostgreSQL Unique Constraints:** Distributed Redis locks introduce network partitions and dual-write divergence. PostgreSQL relational constraints guarantee true ACID linearizability. |
| **PII Redaction** | Service-level field manual masking | **Logback Converter Pipeline:** Manual redaction is error-prone. A composite Logback converter intercepts all logging events at the root appender, guaranteeing zero leakage. |

---

## 7. Failure-Mode Table (What Fails, What the User Sees, How the System Recovers)

| What Fails | What the User Sees | How the System Recovers |
|---|---|---|
| **PostgreSQL Database Outage / Unreachable** | **HTTP 503 Service Unavailable**<br>`{"type":".../service-unavailable", "title":"Service Unavailable", "detail":"Database is temporarily unreachable. Please retry."}`<br>Includes `Retry-After: 30` header. | System rejects batch immediately without volatile in-memory buffering. Upstream HR system catches 503 and retries delivery once the database recovers. Two-level idempotency ensures zero side-effects on replay. |
| **Duplicate Delivery (Network Timeout Replay)** | **HTTP 200 OK**<br>Returns cached response array with original action: `{"external_employee_id":"EMP-1", "status":"ACCEPTED", "action":"CREATED"}`. | Handled at Level 1 via unique constraint on `processed_events(event_id)`. Intercepts unique constraint violation, reads original result, and returns cached ACK without duplicate entity creation. |
| **Out-of-Order Webhook Delivery (Stale Version)** | **HTTP 200 OK** (Per-record skip):<br>`{"external_employee_id":"EMP-1", "status":"SKIPPED", "reason":"STALE_VERSION"}`. | Level 2 ordering check evaluates `incoming_version <= current_version`. The engine harmlessly drops the stale payload, maintaining monotonic forward progress. |
| **Divergent Payloads with Same Version** | **HTTP 409 Conflict** (or per-record `CONFLICT`):<br>`{"external_employee_id":"EMP-1", "status":"CONFLICT", "reason":"VERSION_CONFLICT"}`. | Engine computes SHA-256 hash of payload. Hash divergence for the same version prevents silent data corruption, quarantines the event, and alerts operators for manual inspection. |
| **HR Webhook Attempts to Modify Commute Fields** | **HTTP 200 OK**<br>Employee identity fields updated; transport attributes retained intact. | `FieldOwnershipService` automatically strips Transport-owned fields (`pickup_point`, `special_assistance_needs`) from HR payloads, preserving existing commute configurations. |
| **Transport Admin Attempts to Modify HR Fields** | **HTTP 403 Forbidden**<br>`{"type":".../forbidden", "title":"Forbidden", "detail":"Transport Admin cannot modify HR-owned fields: [name, email]"}`. | Boundary filter detects HR-owned attributes in `PUT /api/v1/employees/{id}/transport-fields` and rejects the request before touching the database. |
| **Truncated / Broken Nightly CSV Export** | **HTTP 202 Accepted**<br>`{"status":"NEEDS_APPROVAL", "missing_ratio":0.42, "detail":"Safety brake triggered: missing ratio exceeds 30%"}`. | **Safety Brake** calculator catches $\ge 30\%$ drop. Deactivations are held in `reconciliation_candidates`. Human operator inspects summary and executes `/approve` or `/reject`. |
| **Stale CSV Export Overwrite Race** | **HTTP 200 OK**<br>Stale export row skipped; real-time database state preserved. | **Snapshot Watermark Guard** compares `db.updated_at >= export.generated_at`. Real-time API updates arriving after export generation are protected from reversion. |
| **Concurrent Reconciliation for Same Tenant** | **HTTP 409 Conflict**<br>`{"type":".../conflict", "title":"Conflict", "detail":"Reconciliation already in progress for tenant"}`. | Tenant-scoped PostgreSQL advisory lock (`pg_try_advisory_lock`) rejects second job without waiting or deadlocking. |
| **Downstream Transit Service Network Timeout** | **Transparent to User**<br>Webhook caller receives 200 OK. Outbox event marked `PENDING`. | `OutboxPoller` retries delivery with exponential backoff ($2^n$, max 60s). If failures reach 10 retries, status transitions to `DEAD_LETTER` for operator replay via `/api/v1/outbox/{id}/replay`. |
| **Process Crash Between DB Write & Publish** | **Transparent to User**<br>Database transaction committed prior to crash. | Transactional outbox ensures employee update and outbox row are committed atomically. On process reboot, `OutboxPoller` claims pending events and delivers them. |
| **Downstream Outbox Replay / Crash Recovery** | **Transparent to Downstream**<br>Zero duplicate booking cancellations. | Downstream consumer verifies `processed_outbox_events.outbox_event_id` in the same transaction. Replays are detected and skipped without repeating side-effects. |

---

