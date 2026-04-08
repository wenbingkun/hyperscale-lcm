-- Add execution strategy fields so jobs can dispatch shell / ansible / ssh commands.
ALTER TABLE job ADD COLUMN IF NOT EXISTS execution_type VARCHAR(32);
ALTER TABLE job ADD COLUMN IF NOT EXISTS execution_payload TEXT;

UPDATE job
SET execution_type = 'DOCKER'
WHERE execution_type IS NULL;

ALTER TABLE job ALTER COLUMN execution_type SET NOT NULL;
ALTER TABLE job ALTER COLUMN execution_type SET DEFAULT 'DOCKER';
