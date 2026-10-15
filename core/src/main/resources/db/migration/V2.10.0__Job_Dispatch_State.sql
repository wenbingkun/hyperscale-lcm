ALTER TABLE job ADD COLUMN IF NOT EXISTS dispatch_attempt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE job ADD COLUMN IF NOT EXISTS dispatched_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_job_status_dispatched ON job(status, dispatched_at);
