-- V1.2.0: Job 表创建
CREATE TABLE IF NOT EXISTS job (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255),
    description TEXT,
    
    -- 资源需求
    required_cpu_cores INTEGER NOT NULL DEFAULT 0,
    required_memory_gb BIGINT NOT NULL DEFAULT 0,
    required_gpu_count INTEGER NOT NULL DEFAULT 0,
    required_gpu_model VARCHAR(64),
    
    -- GPU 拓扑需求
    requires_nvlink BOOLEAN DEFAULT FALSE,
    min_nvlink_bandwidth_gbps INTEGER DEFAULT 0,
    
    -- 状态
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    assigned_node_id VARCHAR(64),
    
    -- 多租户
    tenant_id VARCHAR(64),
    
    -- 时间戳
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    scheduled_at TIMESTAMP,
    completed_at TIMESTAMP,
    
    -- 执行结果
    exit_code INTEGER,
    error_message TEXT
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_job_status ON job(status);
CREATE INDEX IF NOT EXISTS idx_job_tenant ON job(tenant_id);
CREATE INDEX IF NOT EXISTS idx_job_created ON job(created_at DESC);
