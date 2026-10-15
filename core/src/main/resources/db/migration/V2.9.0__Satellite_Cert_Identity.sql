ALTER TABLE discovered_devices ADD COLUMN IF NOT EXISTS satellite_cert_fingerprint VARCHAR(64);
ALTER TABLE satellite ADD COLUMN IF NOT EXISTS cert_fingerprint VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uq_discovered_devices_satellite_cert ON discovered_devices(satellite_cert_fingerprint);
CREATE UNIQUE INDEX IF NOT EXISTS uq_satellite_cert_fingerprint ON satellite(cert_fingerprint);
