-- ==============================================================================
-- HR Roster Sync Service - Flyway Migration V1__init_schema.sql
-- MoveInSync SDE Intern Case Study 5
-- ==============================================================================

-- 1. TENANTS TABLE
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    safety_brake_threshold NUMERIC(4,3) NOT NULL DEFAULT 0.300,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. OFFICES TABLE (tenant-scoped)
CREATE TABLE offices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_office_tenant_code UNIQUE (tenant_id, code)
);

-- 3. EMPLOYEES TABLE (tenant-scoped with field ownership & versioning)
CREATE TABLE employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    external_employee_id VARCHAR(64) NOT NULL,
    current_version BIGINT NOT NULL DEFAULT 1,
    last_payload_hash CHAR(64) NOT NULL,
    record_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    
    -- HR-owned fields (core per Case Study §II)
    name VARCHAR(200) NOT NULL,
    email VARCHAR(255) NOT NULL,
    manager_id VARCHAR(64),
    office_code VARCHAR(64) NOT NULL,
    
    -- HR-owned fields (extended assumptions per §18)
    phone VARCHAR(32),
    shift VARCHAR(64),
    department VARCHAR(100),
    designation VARCHAR(100),
    
    -- Transport-owned fields (core per Case Study §II)
    pickup_point VARCHAR(255),
    special_assistance_needs TEXT,
    
    -- Transport-owned fields (extended assumptions per §18)
    drop_point VARCHAR(255),
    preferred_cab_type VARCHAR(64),
    
    -- Concurrency & Audit
    jpa_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_emp_tenant_ext UNIQUE (tenant_id, external_employee_id),
    CONSTRAINT uq_emp_tenant_id UNIQUE (tenant_id, id)
);

-- 4. PROCESSED_EVENTS TABLE (DB-enforced 2-level idempotency & conflict audit)
CREATE TABLE processed_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id VARCHAR(64) NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    external_employee_id VARCHAR(64) NOT NULL,
    source_version BIGINT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('CREATE', 'UPDATE', 'DEACTIVATE')),
    result VARCHAR(32) NOT NULL CHECK (result IN ('ACCEPTED', 'REJECTED', 'SKIPPED', 'CONFLICT')),
    action VARCHAR(32),
    skip_reason VARCHAR(64),
    validation_errors JSONB,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_processed_event_id UNIQUE (event_id),
    CONSTRAINT uq_processed_emp_version UNIQUE (tenant_id, external_employee_id, source_version)
);

-- 5. OUTBOX_EVENTS TABLE (reliable asynchronous event publishing)
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64) NOT NULL DEFAULT 'EMPLOYEE',
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD_LETTER')),
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ
);

-- 6. PROCESSED_OUTBOX_EVENTS TABLE (downstream consumer idempotency ledger)
CREATE TABLE processed_outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    outbox_event_id UUID NOT NULL UNIQUE,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 7. RECONCILIATION_RUNS TABLE (nightly diff tracking & safety brake state machine)
CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    file_checksum CHAR(64) NOT NULL,
    export_id VARCHAR(100),
    generated_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN (
        'RECEIVED', 'VALIDATING', 'RUNNING', 'NEEDS_APPROVAL', 
        'APPROVED', 'REJECTED', 'APPLYING', 'COMPLETED', 'FAILED'
    )),
    total_in_export INT NOT NULL DEFAULT 0,
    total_active_in_db INT NOT NULL DEFAULT 0,
    new_count INT NOT NULL DEFAULT 0,
    updated_count INT NOT NULL DEFAULT 0,
    unchanged_count INT NOT NULL DEFAULT 0,
    skipped_newer_count INT NOT NULL DEFAULT 0,
    missing_count INT NOT NULL DEFAULT 0,
    missing_ratio NUMERIC(5,4) NOT NULL DEFAULT 0.0000,
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    operator_reason TEXT,
    failure_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_recon_tenant_checksum UNIQUE (tenant_id, file_checksum)
);

-- 8. RECONCILIATION_CANDIDATES TABLE (normalized candidate ledger)
CREATE TABLE reconciliation_candidates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
    external_employee_id VARCHAR(64) NOT NULL,
    candidate_type VARCHAR(32) NOT NULL CHECK (candidate_type IN ('NEW', 'CHANGED', 'MISSING')),
    disposition VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (disposition IN ('PENDING', 'APPLIED', 'SKIPPED_NEWER', 'SKIPPED_BRAKE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 9. BOOKINGS TABLE (transport side-effects)
CREATE TABLE bookings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    pickup_at TIMESTAMPTZ NOT NULL,
    pickup_location VARCHAR(255) NOT NULL,
    drop_location VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED_EMPLOYEE_EXIT')),
    cancellation_reason VARCHAR(255),
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_booking_emp_tenant FOREIGN KEY (tenant_id, employee_id) REFERENCES employees(tenant_id, id) ON DELETE CASCADE
);

-- 10. SEAT_ASSIGNMENTS TABLE (cab seat side-effects)
CREATE TABLE seat_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    cab_id VARCHAR(64) NOT NULL,
    seat_number INT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ASSIGNED' CHECK (status IN ('ASSIGNED', 'FREED')),
    freed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_seat_emp_tenant FOREIGN KEY (tenant_id, employee_id) REFERENCES employees(tenant_id, id) ON DELETE CASCADE
);

-- ==============================================================================
-- INDEXES FOR OPERATIONAL PERFORMANCE
-- ==============================================================================

-- Covering index for active employee lookups during reconciliation diffs
CREATE INDEX idx_emp_tenant_active 
    ON employees(tenant_id) 
    INCLUDE (external_employee_id, record_hash, current_version, updated_at)
    WHERE status = 'ACTIVE';

-- Partial index for fast outbox poller claims
CREATE INDEX idx_outbox_pending 
    ON outbox_events(status, next_attempt_at) 
    WHERE status = 'PENDING';

-- Partial index for active future bookings cancellation
CREATE INDEX idx_bookings_emp_future 
    ON bookings(employee_id, pickup_at) 
    WHERE status = 'ACTIVE';

-- Candidate lookup by run and candidate type
CREATE INDEX idx_recon_candidates_run
    ON reconciliation_candidates(run_id, candidate_type);
