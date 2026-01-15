-- V1.3.0: 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    
    -- 事件信息
    event_type VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32),
    resource_id VARCHAR(64),
    
    -- 操作者
    actor VARCHAR(128),
    tenant_id VARCHAR(64),
    
    -- 详情
    details TEXT,
    ip_address VARCHAR(64),
    
    -- 时间戳
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_audit_resource ON audit_log(resource_id);
CREATE INDEX IF NOT EXISTS idx_audit_tenant ON audit_log(tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_event ON audit_log(event_type);
