# Phase Deployment Closure：把"作者能跑通"收口为"他人能部署运维"

> Updated: 2026-06-11
> Status: **Draft** — 待评审；定稿后按 Step 6 节奏回写 PROJECT_STATUS 与 ROADMAP。
> 由 Claude Code (Fable 5) 编写。
> 约束前提：与 [SOFTWARE_CLOSURE_PHASE_PLAN.md](SOFTWARE_CLOSURE_PHASE_PLAN.md) 一致 —— 无真实 BMC / 裸机设备、无真实 AlertManager secret。本阶段全部工作不依赖外部条件。

## Summary

- Software Closure Round 2 的两项无外部依赖交付物（PXE runbook、load-test 趋势基线）已于 2026-04-18 落地，此后主线休眠约 8 周（main 上仅有定时 CodeQL，全绿）。功能层面五个路线图阶段均已收口。
- 当前离"可用状态"的差距不在功能代码，而在**部署产品化**。本阶段把目标具体化为一条验收口径：
  > 一位未参与开发的运维者，仅凭仓库文档，在一台干净的 Linux + Docker 主机（或一个 k8s 集群）上，用**固定版本号**部署出生产模式（mTLS 开启）的 Core + Satellite + Frontend，完成「登录 → 发现并纳管 mock 设备 → 提交 Job 并看到回调 → 告警路由可见」，且服务重启后状态可恢复。
- 现状证据（2026-06-11 梳理）：
  1. **无版本化发布**：仓库没有任何 git tag；CI `docker-build` job 只推 `latest` + commit SHA（[.github/workflows/ci.yml](../.github/workflows/ci.yml) docker-build job）；没有 CHANGELOG。
  2. **镜像引用三处不一致**：CI 推送 `${DOCKERHUB_USERNAME}/lcm-core`，[helm values.yaml](../helm/hyperscale-lcm/values.yaml) 默认 `example/lcm-core:latest`，[helm README](../helm/hyperscale-lcm/README.md) 写的是 `hyperscale-lcm/core`，[k8s/core-deployment.yaml](../k8s/core-deployment.yaml) 用 `example/lcm-core:latest`。照任何一份文档装都装不起来。
  3. **生产 compose 不完整且有硬编码默认密码**：[docker-compose.prod.yml](../docker-compose.prod.yml) 含 infra + lcm-core + lcm-frontend，但没有 satellite 服务，也没有文档说明 satellite 的部署形态；`POSTGRES_PASSWORD: ${DB_PASSWORD:-O1b2M3x4P5!}` 带可猜默认值 fallback（出现两处）。
  4. **runbook 覆盖面窄**：[runbooks/](runbooks/) 只有 `alertmanager.md` 和 `pxe.md`，缺安装部署、升级（Flyway）、备份恢复、证书轮换文档。
  5. **依赖漂移已造成实际破坏**（2026-06-11 回温验证发现）：本地 Docker Engine 29.1.3 要求最低 API 1.44，而 Quarkus 3.6.4 BOM 锁定的 testcontainers 1.19.3 / docker-java 3.3.4 固定使用 API 1.32，导致 `E2EIntegrationTest` / `RestApiRateLimitTest` / `ImageCatalogResourceTest` 三个依赖 DevServices 的测试在新版 Docker 主机上**确定性失败**（报错 `client version 1.32 is too old`）。GitHub runner 的 Docker 升级到同等版本后 CI 也会同样翻红。
- Phase 定位：**一次"部署产品化"收口**，不是功能扩展。多集群 CRUD、断连恢复场景、覆盖率提升均显式排除（见"不做的事"）。

## Key Changes

以下按执行顺序组织。每步完成后在本地跑对应验证 → commit；禁用 `git commit --no-verify` 与 `git push --force`。

### Step 0 — 基线确认 + 回温记录（纯文档，本文件）

**目标**：固化现状证据与 Phase 边界；同时记录 2026-06-11 回温验证结果：

- Satellite `go test ./... -count=1` ✅ 全绿
- Frontend `npm test`（13 文件 / 29 用例）+ `npm run lint` + `npm run build` ✅ 全绿
- Core `./gradlew check`：155 个测试通过，3 个失败 —— 全部为上述 testcontainers × Docker 29 环境不兼容，**非代码回归**（已用 CI 等价的外部 PG/Redis/Kafka 服务复现确认）

**依赖审计结论（2026-06-11，只记录不动手）**：

| 优先级 | 发现 | 处置 |
|--------|------|------|
| 🔴 P1 | testcontainers 1.19.3 / docker-java 3.3.4 与 Docker Engine ≥29 不兼容（已实测破坏本地测试） | 本阶段 Step 1 修复 |
| 🔴 P1 | frontend `react-router` < 7.14.2 存在 2 个 high 级安全通告（GHSA-49rj-9fvp-4h2h 等 6 项；`npm audit fix` 可在 semver 内修复） | 建议作为独立小 commit 尽快落地，跑 frontend 全套验证 |
| 🟠 P2 | Quarkus 3.6.4 平台偏旧；Gradle 8.5 有 Gradle 9 不兼容弃用警告 | 升级各自独立立项，不进本阶段 |
| 🟢 — | Satellite go.mod 1.24，测试全绿；govulncheck 未运行（本机无该工具） | 后续 CI 可考虑补充 |

**commit**：`docs: add Phase Deployment Closure plan with rewarm verification record`

### Step 1 — 依赖漂移修复：testcontainers 与新版 Docker Engine 兼容

**问题**：testcontainers 1.19.3 不向其 HTTP 传输层传递 API 版本覆盖（`DOCKER_API_VERSION` 无效，已实测），docker-java 3.3.4 固定 API 1.32；Docker Engine ≥29 默认拒绝 <1.44 的客户端。

**修改**：[core/build.gradle](../core/build.gradle) 通过 `resolutionStrategy.force`（或显式版本声明，以实际能压过 `enforcedPlatform` 的方式为准）把 `org.testcontainers:*` 升至 1.20.x+、`com.github.docker-java:*` 升至 3.4.x+，并验证 Quarkus 3.6.4 DevServices 仍能正常拉起 PG/Kafka。

**触发条件**：若 main CI 在本阶段首次推送时已翻红且签名一致，本步立即执行；若 CI 仍绿（runner Docker 尚旧），本步仍应执行——它是时间问题而非概率问题。Quarkus platform 整体升级**不在**本阶段范围。

**验证**：`cd core && ./gradlew check --no-daemon` 在 Docker 29 主机全绿；CI backend-test 绿。涉及 `core/` 测试基建，按 [CI_CONTRACT.md](CI_CONTRACT.md) 验证矩阵执行，先跑 `./scripts/check_ci_contract.sh`。

**commit**：`fix(core): force testcontainers/docker-java versions compatible with Docker Engine >= 29`

### Step 2 — v0.1.0 版本化发布

**修改**：

- 统一镜像命名为单一事实源（建议 `values.yaml` / k8s 清单 / helm README 全部对齐 CI 实际推送的 `<dockerhub-user>/lcm-{core,satellite,frontend}` 形式，或引入显式 registry 变量）。
- CI `docker-build` job 增加 tag 触发：`on.push.tags: ['v*']` 时推送 semver tag 镜像。
- 新建根目录 `CHANGELOG.md`（Keep a Changelog 体例），首条目 v0.1.0 汇总五个阶段能力。
- 打 annotated tag `v0.1.0` 并推送。

**验证**：tag push 后 CI docker-build 产出 `lcm-core:v0.1.0` 等三镜像；helm template 用 v0.1.0 渲染通过 `helm lint`。

**commit**：`ci: publish semver-tagged images on release tags` + `docs: add CHANGELOG with v0.1.0`

### Step 3 — 新增 `runbooks/deployment.md`

**内容**：

- **compose 单机路径**：前置条件 → `generate_keys.sh` → secret 注入规范（环境变量清单，无默认值兜底）→ `docker-compose.prod.yml up` → 健康检查点。
- **helm 路径**：values 必填项清单、Secret 创建、`helm install` 到可登录的完整步骤。
- **satellite 部署形态显式化**：说明 satellite 运行在被管节点侧（k8s 为 DaemonSet；compose 单机演示场景给出独立启动方式），不再留空白。
- 同步修改 [docker-compose.prod.yml](../docker-compose.prod.yml)：**删除两处 `DB_PASSWORD` 默认值 fallback**（缺失时直接 fail-fast），其余口径不变。

**验证**：`docker-compose -f docker-compose.prod.yml config` 在未注入 secret 时报错、注入后通过；`./scripts/check_ci_contract.sh`（`docker-compose*.yml` 属高风险路径）。

**commit**：`docs(runbooks): add production deployment runbook` + `fix(compose): remove default DB password fallback`

### Step 4 — 新增 `runbooks/upgrade-and-backup.md`

**内容**：Flyway 迁移升级流程（含回退口径）、PostgreSQL 备份/恢复步骤、mTLS / JWT 证书轮换流程（基于 `generate_keys.sh` 的产物清单）。纯文档，不改运行时行为。

**commit**：`docs(runbooks): add upgrade, backup and certificate rotation runbook`

### Step 5 — 干净环境验收 walkthrough

**目标**：用 Summary 的验收口径做一次真实走查：找一台干净 Linux + Docker 主机（或全新 k8s namespace），**只看文档**完成部署与主流程验证，把卡点回填到 Step 3/4 的 runbook。

**产物**：runbook 末尾追加"验收记录"段（日期、环境、结果、修正项）。不新建 CI job——`demo-smoke` 已覆盖软件链路门禁，本步验证的是**文档与打包**。

**commit**：`docs(runbooks): record clean-host deployment acceptance walkthrough`

### Step 6 — 文档同步：定稿后回写现状与路线图

与 [SOFTWARE_CLOSURE_PHASE_PLAN.md](SOFTWARE_CLOSURE_PHASE_PLAN.md) Step 5 同节奏：

- 本文件经评审定稿后，下一次 commit 一次性更新 [PROJECT_STATUS.md](PROJECT_STATUS.md)（能力矩阵补"版本化发布 / 部署 runbook"行、刷新 Last Updated 与"下阶段重点"）与 [DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md) `Current Focus`。
- 讨论阶段不提前写"已落地"。

## Public Interfaces

- **不新增或修改任何 REST / gRPC 接口**；`lcm.proto`、`/api/bmc/devices/{id}/...`、PXE / Satellite / Core 运行时契约不变。
- **新增 CI 触发面**：`on.push.tags: ['v*']` 仅影响 docker-build job 的镜像 tag 集合，不改既有 job 结构。
- **行为变化（显式声明）**：`docker-compose.prod.yml` 在未注入 `DB_PASSWORD` 时从"静默使用默认密码"变为"启动失败"。这是有意为之的安全收紧。
- **新增文档**：`runbooks/deployment.md`、`runbooks/upgrade-and-backup.md`、根目录 `CHANGELOG.md`。

## Test Plan

| 层 | 命令 / 动作 | 新增 / 存量 |
|---|---|---|
| CI contract guard | `./scripts/check_ci_contract.sh`（Step 1/2/3 各执行一次） | 存量 |
| Core | `cd core && ./gradlew check --no-daemon`（Docker 29 主机全绿为 Step 1 验收线） | 存量回归 |
| Satellite | `cd satellite && go test ./... -count=1` | 存量回归 |
| Frontend | `cd frontend && npm test && npm run lint && npm run build` | 存量回归 |
| Helm | `helm lint` + `helm template`（v0.1.0 镜像引用渲染） | 存量 |
| Compose | `docker-compose -f docker-compose.prod.yml config`（有/无 secret 两态） | 新增 |
| 发布链路 | tag push → CI 产出三个 `:v0.1.0` 镜像 | 新增 |
| 验收走查 | Step 5 干净环境 walkthrough checklist | 新增 |

## Assumptions

- 真实 BMC / 裸机 / AlertManager secret 在本阶段内仍不可用；外部门控项（BMC 准入、PXE 真实验证、AlertManager 真实送达）保持待命，不进入本阶段范围。
- CI 的 `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` secret 持续有效，tag 推送复用同一凭据。
- testcontainers 1.20.x 与 Quarkus 3.6.4 DevServices 兼容（Step 1 实施时验证；若不兼容，回退方案为在 runbook 中记录 Docker daemon `min-api-version` 配置法并升级 Quarkus platform 立项为独立 phase）。
- GitHub runner 的 Docker 版本会在可预见时间内升级到拒绝 API 1.32 的版本；Step 1 是先手修复而非投机。

## 不做的事

- 不做断连恢复 / 故障注入场景（留给后续 Phase Stability Closure）
- 不做多集群联邦 / Cluster CRUD（长期收敛项）
- 不提升覆盖率门槛（长期收敛项）
- 不整体升级 Quarkus platform / React / Go 工具链（依赖审计结论单独记录，升级各自立项）
- 不引入 External Secrets Operator / Vault / SOPS（维持 Software Closure 阶段既有决策：延至真实 secret 可用前再选型）
- 不触碰 AlertManager / PXE / Redfish readiness 资产
- 不新增 CI job；只给既有 docker-build job 增加 tag 触发
- 不新建带日期后缀的分析文档；现状回写仍走 PROJECT_STATUS.md

## 关键文件清单

**新增**：本文件、`CHANGELOG.md`、`documentation/runbooks/deployment.md`、`documentation/runbooks/upgrade-and-backup.md`

**修改**：[core/build.gradle](../core/build.gradle)（Step 1）、[.github/workflows/ci.yml](../.github/workflows/ci.yml)（Step 2，仅 docker-build tag 集合）、[helm/hyperscale-lcm/values.yaml](../helm/hyperscale-lcm/values.yaml) / [helm README](../helm/hyperscale-lcm/README.md) / [k8s/*.yaml](../k8s/)（Step 2，镜像引用统一）、[docker-compose.prod.yml](../docker-compose.prod.yml)（Step 3，删默认密码）、[PROJECT_STATUS.md](PROJECT_STATUS.md) / [DEVELOPMENT_ROADMAP.md](../DEVELOPMENT_ROADMAP.md)（Step 6）

**不得触碰**：`lcm.proto` 及 gRPC 生成物、Core/Satellite/Frontend 业务代码、load-test / demo-smoke / frontend-e2e job 逻辑、JaCoCo 门禁、AlertManager chart 逻辑、Playwright 资产
