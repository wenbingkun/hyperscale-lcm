-- Create Satellite table
CREATE TABLE satellite (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    hostname VARCHAR(255),
    ip_address VARCHAR(255),
    os_version VARCHAR(255),
    agent_version VARCHAR(255),
    status VARCHAR(255),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    last_heartbeat TIMESTAMP
);

-- Create Node table
CREATE TABLE node (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    cpu_cores INTEGER NOT NULL,
    gpu_count INTEGER NOT NULL,
    gpu_model VARCHAR(255),
    memory_gb BIGINT NOT NULL,
    rack_id VARCHAR(255),
    zone_id VARCHAR(255)
);

-- Note: We assume Satellite uses snake_case column names due to Quarkus default NamingStrategy.
-- If inconsistencies arise, we will adjust.
