-- Add cluster_id to Job table so scheduling can isolate work by cluster.
ALTER TABLE job ADD COLUMN cluster_id VARCHAR(255);

-- Preserve existing jobs by placing them in the default cluster.
UPDATE job SET cluster_id = 'default' WHERE cluster_id IS NULL;

ALTER TABLE job ALTER COLUMN cluster_id SET NOT NULL;
ALTER TABLE job ALTER COLUMN cluster_id SET DEFAULT 'default';
