# BACKEND_CODE_REVIEW_2026-03-12.md

**审核人**: Forge⚙️（后端工程师）  
**日期**: 2026-03-12  
**项目**: hyperscale-lcm  
**路径**: ~/projects/work/hyperscale-lcm  
**约束**: 只读审查，不改动代码

---

## 代码结构概览

| 模块 | 技术栈 | 职责 |
|-----|--------|------|
| Core | Java 21 + Quarkus 3.x + Hibernate Reactive | 调度核心、REST API、gRPC 服务 |
| Satellite | Go 1.21 + gRPC | 节点代理、Docker 执行、心跳上报 |
| 通信 | Kafka (job-queue) + gRPC bidirectional stream | 异步消息 + 实时命令 |

---

## 🔴 高风险问题

### B1 · JobResource.submitJob() - 事务边界与调度竞态

**位置**: `core/src/main/java/com/sc/lcm/core/api/JobResource.java:submitJob()`

**问题代码**:
```java
return Panache.withTransaction(job::persist)
    .replaceWith(Response.status(Response.Status.CREATED)...)
    .invoke(() -> vertx.getDelegate().runOnContext(ignored -> triggerScheduling(jobId, schedulingJob)));
```

**问题分析**:
1. `triggerScheduling` 在 `replaceWith` 之后通过 `invoke()` 异步触发
2. 此时事务可能尚未真正提交（Hibernate Reactive 的异步事务边界）
3. `schedulingJob` 是内存拷贝，未关联持久化上下文
4. 调度服务读取数据库时可能看不到刚提交的作业（读已提交隔离级别下的竞态窗口）

**风险**: 高并发下作业提交后调度器找不到作业，导致作业"丢失"

**修复建议**:
```java
// 方案1: 使用 chain 确保事务完成后再触发
return Panache.withTransaction(job::persist)
    .chain(() -> {
        // 事务已提交，安全触发调度
        triggerScheduling(jobId, schedulingJob);
        return Uni.createFrom().item(Response.created(...).build());
    });

// 方案2: 改用 Kafka Outbox 模式（更可靠）
// 写入作业 + 写入 outbox 表在同一事务，Kafka Connect 读取 outbox 触发调度
```

---

### B2 · JobDispatcher.dispatch() - 空指针与异常吞没

**位置**: `core/src/main/java/com/sc/lcm/core/service/JobDispatcher.java`

**问题代码**:
```java
@Incoming("job-queue-in")
public void dispatch(Job job) {
    if (job == null || job.getAssignedNode() == null) {
        LOG.debug("Ignoring invalid job from Kafka");  // 仅 debug 级别
        return;
    }
    // streamRegistry.sendCommand() 可能抛出异常，但没有 try-catch
}
```

**问题分析**:
1. 无效消息仅记录 debug 日志，生产环境（INFO 级别）下完全静默
2. 消息可能因序列化问题或数据损坏而无效，但运维无法感知
3. `sendCommand()` 抛出异常会导致 Kafka 消费偏移量未提交，可能重复消费或阻塞

**风险**: 作业丢失或 Kafka 消费阻塞，影响整个调度链路

**修复建议**:
```java
@Incoming("job-queue-in")
public void dispatch(Job job) {
    if (job == null || job.getAssignedNode() == null) {
        LOG.error("Invalid job message from Kafka, jobId={}", 
            job != null ? job.getId() : "null");
        metrics.incrementInvalidJobCounter();  // 增加监控指标
        return;  // 确认消息，避免阻塞
    }
    
    try {
        streamRegistry.sendCommand(nodeId, job.getId(), "EXEC_DOCKER", payload, traceContext);
    } catch (Exception e) {
        LOG.error("Failed to dispatch job {} to node {}", job.getId(), nodeId, e);
        metrics.incrementDispatchFailureCounter();
        // 根据异常类型决定是否重试或死信
        throw e;  // 让 Kafka 重试或进入死信队列
    }
}
```

---

## 🟡 中风险问题

### M1 · Satellite 重连逻辑 - 无限重试无退避上限

**位置**: `satellite/cmd/satellite/main.go`

**问题代码**:
```go
for {
    stream, err := client.ConnectStream(bgCtx)
    if err != nil {
        log.Printf("❌ Failed to connect stream: %v. Retrying in 5s...", err)
        time.Sleep(5 * time.Second)  // 固定 5s，无指数退避
        continue
    }
    // ...
}
```

**问题分析**:
- 固定 5 秒重连间隔，无指数退避
- 服务端故障时，所有 satellite 同时重连，形成重连风暴（Thundering Herd）

**修复建议**:
```go
import "github.com/cenkalti/backoff/v4"

// 指数退避 + 抖动
expBackoff := backoff.NewExponentialBackOff()
expBackoff.InitialInterval = 5 * time.Second
expBackoff.MaxInterval = 60 * time.Second
expBackoff.MaxElapsedTime = 0  // 无限重试

for {
    stream, err := client.ConnectStream(bgCtx)
    if err != nil {
        wait := expBackoff.NextBackOff()
        log.Printf("❌ Failed to connect stream: %v. Retrying in %v...", err, wait)
        time.Sleep(wait)
        continue
    }
    expBackoff.Reset()  // 连接成功后重置
    // ...
}
```

---

### M2 · gRPC KeepAlive 配置 - 与负载均衡器兼容性

**位置**: `satellite/cmd/satellite/main.go`

**问题代码**:
```go
var kaParams = keepalive.ClientParameters{
    Time:                30 * time.Second,
    Timeout:             10 * time.Second,
    PermitWithoutStream: true,  // 问题所在
}
```

**问题分析**:
- `PermitWithoutStream: true` 允许在没有活跃 stream 时发送 keepalive
- 某些云负载均衡器（AWS NLB、Azure LB）会静默丢弃空闲连接，或限制 keepalive 频率
- 可能导致连接状态不一致（客户端认为连接存活，实际已被 LB 切断）

**修复建议**:
1. 文档中注明此配置要求
2. 或提供配置开关，允许根据部署环境调整：
```go
type Config struct {
    GRPCKeepAliveWithoutStream bool `env:"GRPC_KEEPALIVE_WITHOUT_STREAM" default:"true"`
}
```

---

## ✅ 代码质量亮点

| 项目 | 说明 |
|-----|------|
| 响应式架构 | Hibernate Reactive + Panache，避免阻塞 I/O |
| 安全通信 | mTLS 双向认证，证书链验证完整 |
| 优雅关闭 | SIGINT/SIGTERM 信号处理，资源清理 |
| 可观测性 | OpenTelemetry 集成，分布式链路追踪 |
| 领域驱动 | DDD 分层清晰，domain/infra/api/service 分离 |

---

## 构建验证结果

```bash
# Core (Java)
cd ~/projects/work/hyperscale-lcm/core
./gradlew build -x test
# ✅ BUILD SUCCESSFUL in 45s

# Satellite (Go)
cd ~/projects/work/hyperscale-lcm/satellite
go build ./cmd/satellite
# ✅ 成功，无错误

go test ./...
# ✅ 全部通过
```

---

## 修复优先级建议

| 优先级 | 问题 | 影响 | 工作量 |
|-------|------|------|--------|
| P0 | B1 事务竞态 | 作业丢失 | 2h |
| P0 | B2 异常吞没 | 调度阻塞 | 1h |
| P1 | M1 重连退避 | 重连风暴 | 2h |
| P2 | M2 LB 兼容性 | 连接稳定性 | 1h |

---

*审查人: Forge⚙️*  
*日期: 2026-03-12*
