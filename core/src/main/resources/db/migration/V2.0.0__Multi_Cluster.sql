-- Add cluster_id to Satellite table for Multi-Cluster Management (Task 8.3)
ALTER TABLE Satellite ADD COLUMN cluster_id VARCHAR(255);

-- Ensure backwards compatibility
UPDATE Satellite SET cluster_id = 'default' WHERE cluster_id IS NULL;

-- Make it non-nullable for consistency after legacy initialization
ALTER TABLE Satellite ALTER COLUMN cluster_id SET NOT NULL;
ALTER TABLE Satellite ALTER COLUMN cluster_id SET DEFAULT 'default';
