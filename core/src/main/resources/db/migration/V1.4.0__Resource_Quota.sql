-- V1.4.0: 资源配额表
CREATE TABLE IF NOT EXISTS resource_quota (
    tenant_id VARCHAR(64) PRIMARY KEY,
    
    -- 资源限制
    max_cpu_cores INTEGER DEFAULT 1000,
    max_memory_gb BIGINT DEFAULT 4096,
    max_gpu_count INTEGER DEFAULT 100,
    max_concurrent_jobs INTEGER DEFAULT 50,
    
    -- 配额状态
    enabled BOOLEAN DEFAULT TRUE
);

-- 插入默认租户配额
INSERT INTO resource_quota (tenant_id, max_cpu_cores, max_memory_gb, max_gpu_count, max_concurrent_jobs)
VALUES ('default', 1000, 4096, 100, 50)
ON CONFLICT (tenant_id) DO NOTHING;
