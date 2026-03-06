-- Create Discovered Devices table (Zero-Touch Provisioning Pool)
CREATE TABLE discovered_devices (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    ip_address VARCHAR(255) NOT NULL,
    mac_address VARCHAR(255),
    hostname VARCHAR(255),
    discovery_method VARCHAR(50),
    status VARCHAR(50) DEFAULT 'PENDING',
    discovered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_probed_at TIMESTAMP,
    inferred_type VARCHAR(100),
    open_ports TEXT,
    bmc_address VARCHAR(255),
    notes TEXT,
    tenant_id VARCHAR(255)
);

-- Index for frequent queries
CREATE INDEX idx_discovered_devices_status ON discovered_devices(status);
CREATE INDEX idx_discovered_devices_ip ON discovered_devices(ip_address);
CREATE INDEX idx_discovered_devices_tenant ON discovered_devices(tenant_id);
