# HYPERSCALE_LCM_IMPLEMENTATION_PLAN.md

**项目**: hyperscale-lcm 万级节点生命周期管理系统  
**制定日期**: 2026-03-12  
**制定人**: Forge⚙️ (Backend)  
**状态**: 基于三方审查报告制定

---

## 一、问题总览

### 阻塞项 (Blocker) - 必须修复才能生产
| ID | 问题 | 来源 | 影响 |
|----|------|------|------|
| B1 | QuotaService 竞态条件 | Architect | 配额被超卖 |
| B2 | HeartbeatSyncService 全量扫描 | Architect | 万级节点性能瓶颈 |
| B3 | JobResource.submitJob() 事务竞态 | Backend | 作业丢失 |
| B4 | JobDispatcher.dispatch() 异常吞没 | Backend | 调度链路阻塞 |

### 重要问题 (Major)
| ID | 问题 | 来源 |
|----|------|------|
| M1 | SchedulingService 阻塞调用 | Architect |
| M2 | 配额检查缺租户隔离 | Architect |
| M3 | mTLS 证书无轮换方案 | Architect |
| M4 | Satellite 重连无退避 | Backend |
| M5 | gRPC KeepAlive LB 兼容性 | Backend |

---

## 二、实施阶段规划

### Phase 1: 紧急修复 (Week 1) — 解锁生产阻塞项

**目标**: 修复 4 个 Blocker，达到可生产状态

#### Week 1-1: QuotaService 竞态修复 (B1)
**负责人**: Backend  
**工时**: 2 天

**问题**: `checkJobSubmission` 先 `countByStatus(RUNNING)` 再允许提交，两步之间没有分布式锁

**方案对比**:
| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|--------|
| 数据库悲观锁 | 简单可靠 | 并发性能下降 | ⭐⭐⭐ |
| Redis + Lua 原子 | 高性能 | 引入外部依赖 | ⭐⭐⭐⭐ |
| 乐观锁重试 | 无锁 | 高并发下重试多 | ⭐⭐ |

**实施步骤**:
1. **Day 1**: 实现 Redis + Lua 原子校验
   ```lua
   -- quota_check.lua
   local key = KEYS[1]  -- tenant:{tenantId}:running_count
   local limit = tonumber(ARGV[1])
   local current = tonumber(redis.call('GET', key) or 0)
   if current >= limit then
       return 0  -- 拒绝
   end
   redis.call('INCR', key)
   return 1  -- 允许
   ```
2. **Day 1**: 修改 `QuotaService.checkJobSubmission()` 调用 Lua 脚本
3. **Day 2**: 作业完成/取消时 DECR 计数器
4. **Day 2**: 单元测试 + 并发压测 (1000 并发提交)

**验收标准**:
- [ ] 1000 并发提交，配额零超卖
- [ ] 单元测试覆盖率 > 80%

---

#### Week 1-2: HeartbeatSyncService 增量同步 (B2)
**负责人**: Backend  
**工时**: 3 天

**问题**: 每 30s 全量遍历 `Satellite.listAllReactive()`，万级节点时事务过长

**方案**: 改为 Redis 发布订阅 + 增量同步

**实施步骤**:
1. **Day 1**: 新增 `SatelliteChangeEvent` 领域事件
   ```java
   public record SatelliteChangeEvent(
       String satelliteId,
       ChangeType type,  // REGISTERED, HEARTBEAT_UPDATED, OFFLINE
       Instant timestamp
   ) {}
   ```
2. **Day 1**: 心跳更新时发布事件到 Redis Pub/Sub
3. **Day 2**: 重构 `HeartbeatSyncService` 订阅事件，仅处理变更节点
4. **Day 2**: 增加批次上限控制 (单次最多处理 100 个节点)
5. **Day 3**: 压测对比 (全量 vs 增量)

**验收标准**:
- [ ] 万级节点场景下，同步延迟 < 1s
- [ ] CPU 使用率下降 50%+

---

#### Week 1-3: JobResource 事务竞态修复 (B3)
**负责人**: Backend  
**工时**: 1 天

**问题**: `submitJob()` 中调度在事务提交前触发，可能读不到新作业

**实施步骤**:
1. **Day 1**: 修改 `JobResource.submitJob()`
   ```java
   return Panache.withTransaction(job::persist)
       .chain(() -> {
           // 事务已提交，安全触发
           triggerScheduling(jobId, schedulingJob);
           return Uni.createFrom().item(
               Response.created(URI.create("/api/jobs/" + jobId))
                   .entity(new JobResponse(jobId, JobStatus.PENDING.name(), "Job submitted"))
                   .build()
           );
       });
   ```
2. **Day 1**: 增加集成测试验证事务边界

**验收标准**:
- [ ] 1000 并发提交，零作业丢失

---

#### Week 1-4: JobDispatcher 异常处理 (B4)
**负责人**: Backend  
**工时**: 1 天

**问题**: 无效消息仅 debug 日志，异常无处理可能导致 Kafka 阻塞

**实施步骤**:
1. **Day 1**: 修改 `JobDispatcher.dispatch()`
   - 无效消息改为 ERROR 级别 + Metrics 计数器
   - 增加 try-catch 包装 `sendCommand()`
   - 区分可重试异常与死信异常
2. **Day 1**: 配置 Kafka Dead Letter Topic

**验收标准**:
- [ ] 无效消息触发告警
- [ ] 异常消息进入 DLQ，不阻塞消费

---

### Phase 2: 性能优化 (Week 2) — Major 问题修复

#### Week 2-1: SchedulingService 异步化 (M1)
**负责人**: Backend  
**工时**: 2 天

**问题**: `solverManager.solve()` 同步阻塞，阻塞 Quarkus Event Loop

**实施步骤**:
1. **Day 1**: 使用 `@Blocking` 或 `Uni.createFrom().item(() -> {...}).runSubscriptionOn(...)`
2. **Day 1**: 配置 Worker Pool 隔离调度线程
3. **Day 2**: 压测验证 Event Loop 不被阻塞

---

#### Week 2-2: 配额租户隔离 (M2)
**负责人**: Backend  
**工时**: 1 天

**问题**: `Job.countByStatus(RUNNING)` 统计全局，非租户级别

**实施步骤**:
1. **Day 1**: 修改查询加 `WHERE tenant_id = ?`
2. **Day 1**: 更新 Redis Key 结构为 `tenant:{tenantId}:running_count`

---

#### Week 2-3: Satellite 重连退避 (M4)
**负责人**: Backend  
**工时**: 1 天

**实施步骤**:
1. **Day 1**: 引入 `github.com/cenkalti/backoff/v4`
2. **Day 1**: 实现指数退避 + 抖动 (5s → 60s max)

---

#### Week 2-4: gRPC KeepAlive 配置化 (M5)
**负责人**: Backend  
**工时**: 0.5 天

**实施步骤**:
1. **Day 1**: 添加环境变量配置 `GRPC_KEEPALIVE_WITHOUT_STREAM`
2. **Day 1**: 更新部署文档说明 LB 兼容性

---

### Phase 3: 生产准备 (Week 3) — 证书与监控

#### Week 3-1: mTLS 证书轮换方案 (M3)
**负责人**: Backend + Architect  
**工时**: 3 天

**实施步骤**:
1. **Day 1-2**: 设计证书轮换流程
   - 支持热加载（不重启服务）
   - 证书过期前 30 天告警
   - 自动轮换脚本
2. **Day 3**: 实现 `CertWatcher` 监听证书文件变更

---

#### Week 3-2: 监控告警完善
**负责人**: Backend  
**工时**: 2 天

**实施步骤**:
1. **Day 1**: 增加关键 Metrics
   - `lcm_job_submit_total` / `lcm_job_submit_failed`
   - `lcm_quota_check_total` / `lcm_quota_exceeded`
   - `lcm_satellite_reconnect_total`
2. **Day 2**: 配置 Prometheus Alert Rules

---

## 三、里程碑与验收

| 里程碑 | 日期 | 交付物 | 验收标准 |
|--------|------|--------|----------|
| M1 | Week 1 结束 | Blocker 修复 | 4 个 Blocker 修复 + 压测通过 |
| M2 | Week 2 结束 | Major 修复 | 5 个 Major 修复 + 性能提升 |
| M3 | Week 3 结束 | 生产就绪 | 证书轮换 + 监控 + 文档 |

---

## 四、风险与应对

| 风险 | 概率 | 影响 | 应对 |
|------|------|------|------|
| Redis Lua 脚本性能不达预期 | 中 | 高 | 备选方案：数据库悲观锁 |
| 增量同步事件丢失 | 低 | 高 | Redis Stream 持久化 + 消费确认 |
| 证书热加载实现复杂 | 中 | 中 | 降级为滚动重启方案 |

---

## 五、资源需求

| 角色 | 人力 | 说明 |
|------|------|------|
| Backend | 1 人 × 3 周 | 主实施 |
| Architect | 0.5 人 × 3 周 | 方案评审 |
| QA | 0.5 人 × 2 周 | 压测与验收 |

---

*制定日期: 2026-03-12*  
*制定人: Forge⚙️*
