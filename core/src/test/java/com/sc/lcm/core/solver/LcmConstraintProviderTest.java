package com.sc.lcm.core.solver;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import com.sc.lcm.core.domain.Job;
import com.sc.lcm.core.domain.LcmSolution;
import com.sc.lcm.core.domain.Node;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Timefold 约束求解器测试
 * 验证资源容量约束的正确性
 */
@QuarkusTest
public class LcmConstraintProviderTest {

        @Inject
        ConstraintVerifier<LcmConstraintProvider, LcmSolution> constraintVerifier;

        @Test
        @DisplayName("测试 CPU 容量超限应产生惩罚")
        void testCpuCapacityConstraint() {
                // 创建一个只有 4 核的节点
                Node node = new Node("node-1", 4, 16, 0, null);

                // 创建一个需要 8 核的作业
                Job job = new Job("job-1", 8, 8, 0, null);
                job.setAssignedNode(node);

                // 验证约束：应该因为超出 4 核而产生惩罚
                constraintVerifier.verifyThat(LcmConstraintProvider::cpuCapacity)
                                .given(job)
                                .penalizesBy(4); // 8 - 4 = 4 核超限
        }

        @Test
        @DisplayName("测试 GPU 容量超限应产生惩罚")
        void testGpuCapacityConstraint() {
                // 创建一个有 2 个 A100 GPU 的节点
                Node node = new Node("node-1", 32, 128, 2, "A100");

                // 创建一个需要 4 个 GPU 的作业
                Job job = new Job("job-1", 4, 8, 4, "A100");
                job.setAssignedNode(node);

                // 验证约束：应该因为超出 2 个 GPU 而产生惩罚
                constraintVerifier.verifyThat(LcmConstraintProvider::gpuCountCapacity)
                                .given(job)
                                .penalizesBy(2); // 4 - 2 = 2 GPU 超限
        }

        @Test
        @DisplayName("测试内存容量超限应产生惩罚")
        void testMemoryCapacityConstraint() {
                // 创建一个只有 64GB 内存的节点
                Node node = new Node("node-1", 32, 64, 0, null);

                // 创建一个需要 128GB 内存的作业
                Job job = new Job("job-1", 4, 128, 0, null);
                job.setAssignedNode(node);

                // 验证约束：应该因为超出 64GB 而产生惩罚
                constraintVerifier.verifyThat(LcmConstraintProvider::memoryCapacity)
                                .given(job)
                                .penalizesBy(64); // 128 - 64 = 64GB 超限
        }

        @Test
        @DisplayName("测试资源充足时无惩罚")
        void testNoViolationWhenResourcesSufficient() {
                // 创建资源充足的节点
                Node node = new Node("node-1", 32, 128, 4, "A100");

                // 创建资源需求在限制内的作业
                Job job = new Job("job-1", 8, 32, 2, "A100");
                job.setAssignedNode(node);

                // 验证所有约束都不应产生惩罚
                constraintVerifier.verifyThat(LcmConstraintProvider::cpuCapacity)
                                .given(job)
                                .penalizesBy(0);

                constraintVerifier.verifyThat(LcmConstraintProvider::memoryCapacity)
                                .given(job)
                                .penalizesBy(0);

                constraintVerifier.verifyThat(LcmConstraintProvider::gpuCountCapacity)
                                .given(job)
                                .penalizesBy(0);
        }
}
