-- Phase 7: session-aware Redfish transport, capability snapshots, and per-device auth overrides

ALTER TABLE credential_profiles ADD COLUMN IF NOT EXISTS redfish_auth_mode VARCHAR(50);

ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS redfish_auth_mode_override VARCHAR(50);
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS last_successful_auth_mode VARCHAR(50);
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS last_auth_failure_code VARCHAR(50);
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS last_auth_failure_reason TEXT;
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS bmc_capabilities TEXT;
ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS last_capability_probe_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_discovered_devices_capability_probe_at ON discovered_devices(last_capability_probe_at);
