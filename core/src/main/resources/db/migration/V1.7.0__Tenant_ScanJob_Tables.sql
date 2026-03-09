-- V1.7.0: Create missing tenants and scan_jobs tables

-- Tenants table (multi-tenant resource isolation)
CREATE TABLE IF NOT EXISTS tenants (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- Resource quotas
    cpu_quota INTEGER DEFAULT 1000,
    memory_quota_gb BIGINT DEFAULT 4096,
    gpu_quota INTEGER DEFAULT 100,
    max_concurrent_jobs INTEGER DEFAULT 50,

    -- Resource usage stats
    cpu_used INTEGER DEFAULT 0,
    memory_used_gb BIGINT DEFAULT 0,
    gpu_used INTEGER DEFAULT 0,
    running_jobs INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_tenants_status ON tenants(status);

-- Insert default tenant
INSERT INTO tenants (id, name, description, status)
VALUES ('default', 'Default Tenant', 'Default system tenant', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- Scan Jobs table (network scan task records)
CREATE TABLE IF NOT EXISTS scan_jobs (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    target VARCHAR(255) NOT NULL,
    ports VARCHAR(255) DEFAULT '22,8080,9000,623',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    total_ips INTEGER DEFAULT 0,
    scanned_count INTEGER DEFAULT 0,
    discovered_count INTEGER DEFAULT 0,
    progress_percent INTEGER DEFAULT 0,
    error_message TEXT,
    created_by VARCHAR(255),
    tenant_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_scan_jobs_status ON scan_jobs(status);
CREATE INDEX IF NOT EXISTS idx_scan_jobs_created ON scan_jobs(created_at DESC);
