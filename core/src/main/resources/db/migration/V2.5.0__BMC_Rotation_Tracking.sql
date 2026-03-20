-- Track when a BMC managed account was last rotated

ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS last_rotation_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_discovered_devices_last_rotation ON discovered_devices(last_rotation_at);
