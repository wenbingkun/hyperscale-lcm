package com.sc.lcm.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Job 实体单元测试
 * 验证作业状态枚举、NVLink 需求和构造器
 */
class JobTest {

    @Test
    @DisplayName("默认状态应为 PENDING")
    void testDefaultStatus() {
        Job job = new Job("j1", 8, 32, 4, "A100");
        assertEquals(Job.JobStatus.PENDING, job.getStatus());
    }

    @Test
    @DisplayName("默认优先级应为 5")
    void testDefaultPriority() {
        Job job = new Job("j1", 8, 32, 4, "A100");
        assertEquals(5, job.getPriority());
    }

    @Test
    @DisplayName("默认可被抢占")
    void testDefaultPreemptible() {
        Job job = new Job("j1", 8, 32, 4, "A100");
        assertTrue(job.isPreemptible());
    }

    @Test
    @DisplayName("NVLink 需求构造器应正确设置")
    void testNvLinkRequirements() {
        Job job = new Job("j2", 16, 64, 8, "H100", true, 600);
        assertTrue(job.isRequiresNvlink());
        assertEquals(600, job.getMinNvlinkBandwidthGbps());
    }

    @Test
    @DisplayName("无 NVLink 需求时应为默认值")
    void testNoNvLinkRequirements() {
        Job job = new Job("j3", 8, 32, 4, "A100");
        assertFalse(job.isRequiresNvlink());
        assertEquals(0, job.getMinNvlinkBandwidthGbps());
    }

    @Test
    @DisplayName("状态枚举应包含所有值")
    void testAllJobStatuses() {
        assertEquals(6, Job.JobStatus.values().length);
        assertNotNull(Job.JobStatus.valueOf("PENDING"));
        assertNotNull(Job.JobStatus.valueOf("SCHEDULED"));
        assertNotNull(Job.JobStatus.valueOf("RUNNING"));
        assertNotNull(Job.JobStatus.valueOf("COMPLETED"));
        assertNotNull(Job.JobStatus.valueOf("FAILED"));
        assertNotNull(Job.JobStatus.valueOf("CANCELLED"));
    }
}
