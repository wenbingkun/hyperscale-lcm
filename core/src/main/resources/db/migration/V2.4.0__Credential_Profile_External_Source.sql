-- External bootstrap credential sources from CMDB / delivery ledger

ALTER TABLE credential_profiles ADD COLUMN IF NOT EXISTS source_type VARCHAR(50) DEFAULT 'MANUAL';
ALTER TABLE credential_profiles ADD COLUMN IF NOT EXISTS external_ref VARCHAR(255);
ALTER TABLE credential_profiles ADD COLUMN IF NOT EXISTS hostname_pattern VARCHAR(255);
ALTER TABLE credential_profiles ADD COLUMN IF NOT EXISTS ip_address_pattern VARCHAR(255);
ALTER TABLE credential_profiles ADD COLUMN IF NOT EXISTS mac_address_pattern VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_credential_profiles_source_ref ON credential_profiles(source_type, external_ref);
