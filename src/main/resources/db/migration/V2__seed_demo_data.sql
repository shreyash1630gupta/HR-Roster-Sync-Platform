-- ==============================================================================
-- Flyway Migration V2__seed_demo_data.sql
-- Demo seed data for local testing, reviewer evaluation, and integration tests
-- ==============================================================================

-- Seed Tenants
INSERT INTO tenants (id, code, name, safety_brake_threshold) VALUES
    ('11111111-1111-1111-1111-111111111111', 'tenant-infosys', 'Infosys Technologies Limited', 0.300),
    ('22222222-2222-2222-2222-222222222222', 'tenant-tcs', 'Tata Consultancy Services', 0.300)
ON CONFLICT (code) DO NOTHING;

-- Seed Offices for tenant-infosys
INSERT INTO offices (id, tenant_id, code, name, city, active) VALUES
    ('11111111-aaaa-1111-aaaa-111111111111', '11111111-1111-1111-1111-111111111111', 'BLR-EC', 'Electronic City Phase 1', 'Bengaluru', true),
    ('11111111-bbbb-1111-bbbb-111111111111', '11111111-1111-1111-1111-111111111111', 'BLR-MTH', 'Manyata Tech Park', 'Bengaluru', true),
    ('11111111-cccc-1111-cccc-111111111111', '11111111-1111-1111-1111-111111111111', 'HYD-HITEC', 'HITEC City Campus', 'Hyderabad', true),
    ('11111111-dddd-1111-dddd-111111111111', '11111111-1111-1111-1111-111111111111', 'NOIDA-SEC62', 'Sector 62 Campus', 'Noida', true)
ON CONFLICT (tenant_id, code) DO NOTHING;

-- Seed Offices for tenant-tcs
INSERT INTO offices (id, tenant_id, code, name, city, active) VALUES
    ('22222222-aaaa-2222-aaaa-222222222222', '22222222-2222-2222-2222-222222222222', 'BLR-WHITEFIELD', 'Whitefield IT Park', 'Bengaluru', true),
    ('22222222-bbbb-2222-bbbb-222222222222', '22222222-2222-2222-2222-222222222222', 'PUNE-HINJ', 'Hinjawadi Phase 3', 'Pune', true)
ON CONFLICT (tenant_id, code) DO NOTHING;

