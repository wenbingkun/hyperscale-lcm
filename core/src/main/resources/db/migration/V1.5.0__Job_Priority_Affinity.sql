-- V1.5.0: Job 表添加优先级和亲和性字段
ALTER TABLE job ADD COLUMN IF NOT EXISTS priority INTEGER DEFAULT 5;
ALTER TABLE job ADD COLUMN IF NOT EXISTS preemptible BOOLEAN DEFAULT TRUE;
ALTER TABLE job ADD COLUMN IF NOT EXISTS node_selector VARCHAR(255);

-- 优先级索引（用于高优先级作业优先调度）
CREATE INDEX IF NOT EXISTS idx_job_priority ON job(priority DESC, created_at ASC);
