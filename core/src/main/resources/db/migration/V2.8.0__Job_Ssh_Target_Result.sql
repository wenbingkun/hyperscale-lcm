-- SSH task jobs record the approved target device and the bounded command output.
ALTER TABLE job ADD COLUMN IF NOT EXISTS target_device_id VARCHAR(64);
ALTER TABLE job ADD COLUMN IF NOT EXISTS target_host VARCHAR(255);
ALTER TABLE job ADD COLUMN IF NOT EXISTS result_output TEXT;
