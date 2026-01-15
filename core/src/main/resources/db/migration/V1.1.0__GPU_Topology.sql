-- Hyperscale LCM v1.1.0: GPU Topology Support
-- Adds NVLink/NVSwitch topology fields for GPU scheduling optimization

-- Add GPU topology columns to node table
ALTER TABLE node ADD COLUMN IF NOT EXISTS gpu_topology VARCHAR(50);
ALTER TABLE node ADD COLUMN IF NOT EXISTS nvlink_bandwidth_gbps INTEGER DEFAULT 0;
ALTER TABLE node ADD COLUMN IF NOT EXISTS ib_fabric_id VARCHAR(100);

-- Create index for efficient Infiniband fabric queries
CREATE INDEX IF NOT EXISTS idx_node_ib_fabric ON node(ib_fabric_id);

-- Create index for GPU topology queries
CREATE INDEX IF NOT EXISTS idx_node_gpu_topology ON node(gpu_topology);

COMMENT ON COLUMN node.gpu_topology IS 'GPU interconnect type: NVLink, NVSwitch, PCIe';
COMMENT ON COLUMN node.nvlink_bandwidth_gbps IS 'NVLink bandwidth in GB/s (e.g., A100=600, H100=900)';
COMMENT ON COLUMN node.ib_fabric_id IS 'Infiniband switch/fabric ID for cross-node GPU communication';
