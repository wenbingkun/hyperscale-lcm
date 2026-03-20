-- Zero-touch claim planning for BMC onboarding

ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS manufacturer_hint VARCHAR(255);
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS model_hint VARCHAR(255);
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS recommended_redfish_template VARCHAR(255);
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS auth_status VARCHAR(50) DEFAULT 'PENDING';
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS claim_status VARCHAR(50) DEFAULT 'DISCOVERED';
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS credential_profile_id VARCHAR(255);
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS credential_profile_name VARCHAR(255);
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS credential_source VARCHAR(50);
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS claim_message TEXT;
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS last_auth_attempt_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_discovered_devices_auth_status ON discovered_devices(auth_status);
CREATE INDEX IF NOT EXISTS idx_discovered_devices_claim_status ON discovered_devices(claim_status);
CREATE INDEX IF NOT EXISTS idx_discovered_devices_profile_id ON discovered_devices(credential_profile_id);

CREATE TABLE IF NOT EXISTS credential_profiles (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    protocol VARCHAR(50) DEFAULT 'REDFISH',
    enabled BOOLEAN DEFAULT TRUE,
    auto_claim BOOLEAN DEFAULT TRUE,
    priority INTEGER DEFAULT 100,
    vendor_pattern VARCHAR(255),
    model_pattern VARCHAR(255),
    subnet_cidr VARCHAR(255),
    device_type VARCHAR(100),
    redfish_template VARCHAR(255),
    username_secret_ref VARCHAR(255),
    password_secret_ref VARCHAR(255),
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_credential_profiles_name ON credential_profiles(name);
CREATE INDEX IF NOT EXISTS idx_credential_profiles_enabled_priority ON credential_profiles(enabled, priority);
