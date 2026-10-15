ALTER TABLE job ADD COLUMN IF NOT EXISTS dispatch_attempt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE job ADD COLUMN IF NOT EXISTS dispatched_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_job_status_dispatched ON job(status, dispatched_at);

-- Older Core versions did not record dispatch claims. Active rows may already have executed;
-- never interpret a missing marker as proof that it is safe to send the command again.
UPDATE job
SET status = 'UNKNOWN',
    error_message = 'Dispatch was not tracked before this upgrade; the command may have executed. Verify the result manually; do not resend automatically.'
WHERE status IN ('SCHEDULED', 'RUNNING');
