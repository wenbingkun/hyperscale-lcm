# Phase Demo Smoke：把 demo.sh 真实后端链路接入 CI（P1 收口）

> Updated: 2026-04-16
> 由 Claude code 编写。

## Summary

- Phase Demo Smoke 专注于把当前仓库中**已存在但从未被 CI 门禁保护**的真实后端端到端链路接入 GitHub Actions，是继 Phase AlertManager（PR #20）、Phase Playwright E2E（PR #21）之后的下一条自然主线，负责收口 [PROJECT_STATUS.md](PROJECT_STATUS.md) 中 "Demo smoke 扩展（与真实后端链路的端到端冒烟）" 这条 P1 阻塞项。
- 当前状态梳理（2026-04-16）：
  1. [scripts/demo.sh](../scripts/demo.sh) 已经把完整链路**脚本化**：docker-compose（postgres/redis/kafka/jaeger）→ mock Redfish (HTTPS) → mock SSH → Core → Satellite → JWT 登录 → WebSocket 订阅 → gRPC `ReportDiscovery` → credential profile + claim → 托管账户验证 → 提交 SSH job → 等待 `JOB_STATUS` WebSocket 事件 → 产出 JSON summary。唯独**没有任何 CI job 跑过它**，[.github/workflows/ci.yml](../.github/workflows/ci.yml) 里完全没有它的痕迹。
  2. [core/src/test/java/com/sc/lcm/core/E2EIntegrationTest.java](../core/src/test/java/com/sc/lcm/core/E2EIntegrationTest.java) 只覆盖 Core 自身的两条 happy path（Docker / Shell job），**不涉及真实 Satellite + gRPC + Kafka + WebSocket** 的真实联动——调度/回调/推送链路没有任何 PR 门禁保护。
  3. Playwright E2E（PR #21 刚落地）覆盖 7 类主流程但**100% API mock**：[frontend/e2e/fixtures/api-mocks.ts](../frontend/e2e/fixtures/api-mocks.ts) 拦截 `/api/*` + `addInitScript` 替换 `window.WebSocket`。UI 契约有门禁，但真实后端链路没有。[PROJECT_STATUS.md:21](PROJECT_STATUS.md) 的描述对此**有夸大**，本 Phase 同时修正文档措辞。
  4. [scripts/demo.sh](../scripts/demo.sh) 当前两个生命周期函数对 CI 不友好：`start_core` 用 `./gradlew quarkusDev`（dev mode 慢且不稳定），`start_satellite` 用 `docker run golang:1.24.7 go run ./cmd/satellite`（运行时下载 go modules）。**但两个函数都带 "已存在则跳过" 的探测**（`curl /q/openapi` / `docker ps` 容器名），CI 只要在调用 demo.sh 前先把 Core 和 Satellite 预启动好，就能**原样复用** demo.sh 的所有场景逻辑，不需要侵入式重构。
  5. 唯一的例外是 `start_satellite`：它用容器名探测而非 HTTP 探测，CI 预启动的 host 进程不会被探测到——需要给 demo.sh 加**唯一**一个环境变量开关 `LCM_DEMO_SKIP_SATELLITE` 绕过这个函数。
- Phase 定位：**一次"把已存在但未激活的 smoke 链路接入 CI"的收口工作**，不是 E2E 基础设施重建。
- 明确**不做**：把 Playwright 切到真实后端（留作独立 follow-up phase）；重写 demo.sh 的任何场景函数；引入新的 smoke 断言框架（bats / shellspec）；把 smoke 产出的 JSON 纳入 baseline 趋势比对（那是 load-test baseline 的 P2 方向）；触碰 CI `services:` 块或 `backend-test` / `load-test` job 结构；多 Redfish 固件矩阵；失败恢复 / 断连场景；性能阈值。

## Key Changes

以下按执行顺序组织。每步完成后必须在本地跑对应验证 → 自审 4 问 → commit；禁用 `git commit --no-verify` 与 `git push --force`。

### Step 0 — 基线确认（纯文档）

**目标**：本份 `DEMO_SMOKE_PHASE_PLAN.md` 固化现状 + Phase 边界，让所有后续 step 不得再动"切 Playwright 真实后端"、"重写 demo.sh 场景函数"、"跑多固件矩阵"等已知边界条件。

**自审 4 问**：
- 文档是否把 Phase 边界写死（避免 scope creep）？
- 是否列出所有现有文件的锚点以便后续 PR 审查参照？
- 是否标记好 "follow-up"（Playwright 切真实后端、多固件矩阵、smoke JSON baseline）？
- commit 后 CI 是否仍然绿（纯文档改动应无风险）？

**commit**：`docs: add Phase Demo Smoke productionization plan`

### Step 1 — demo.sh 增加 `LCM_DEMO_SKIP_SATELLITE` 开关

**修改**：[scripts/demo.sh](../scripts/demo.sh) 的 `start_satellite` 函数起始位置插入：

```bash
if [[ -n "${LCM_DEMO_SKIP_SATELLITE:-}" ]]; then
  log "LCM_DEMO_SKIP_SATELLITE set — assuming satellite managed externally"
  return
fi
```

同时在 `usage()` 的 `Environment overrides:` 段追加一行说明。

**为什么只补 Satellite 而不补 Core**：Core 的探测是 `curl $CORE_URL/q/openapi`，CI 预启动 Core 后能被自然探测到并跳过；Satellite 的探测是 `docker ps --format '{{.Names}}' | grep -Fxq "$SATELLITE_CONTAINER_NAME"`，host 进程不会匹配，必须显式跳过。

**本地验证**：
- `LCM_DEMO_SKIP_SATELLITE=1 ./scripts/demo.sh cleanup` 不应报错（快速确认 flag 不破坏 cleanup 路径）
- `./scripts/demo.sh run` 不传环境变量时行为保持不变（回归测试，默认路径不受影响）

**自审 4 问**：
- 环境变量是否使用 `${LCM_DEMO_SKIP_SATELLITE:-}` 保护 `set -u`？
- 日志文案是否和其他 skip 分支（Core / Satellite 探测跳过）一致？
- 是否破坏了默认本地路径？（默认不传变量时 `-n ""` → false → 原路径不变）
- `usage()` 是否同步更新？

**commit**：`feat(scripts): allow demo.sh to defer satellite lifecycle to caller`

### Step 2 — 新增 `scripts/ci_demo_smoke.sh` 驱动器

**新文件**：[scripts/ci_demo_smoke.sh](../scripts/ci_demo_smoke.sh)，职责：

- `set -euo pipefail` + 彩色日志，风格对齐 [scripts/check_ci_contract.sh](../scripts/check_ci_contract.sh)
- 前置断言：Core fast-jar (`core/build/quarkus-app/quarkus-run.jar`) 和 Satellite 二进制 (`satellite/satellite`) 必须已经存在（由 CI workflow 的 build step 产出）
- 预启动 Core：后台运行 `java -jar core/build/quarkus-app/quarkus-run.jar`，env 与 [.github/workflows/ci.yml](../.github/workflows/ci.yml) 的 load-test job 保持一致（`QUARKUS_DATASOURCE_*` / `QUARKUS_REDIS_HOSTS` / `KAFKA_BOOTSTRAP_SERVERS` / `LCM_DISCOVERY_REQUIRE_APPROVAL=false` / `QUARKUS_OTEL_SDK_DISABLED=true`）；记录 PID，轮询 `/health/ready` 直到就绪或超时
- 预启动 Satellite：后台运行 `./satellite/satellite --cluster "$LCM_DEMO_CLUSTER"`，env 同样参照 demo.sh 的 docker 版本（`LCM_CORE_ADDR` / `LCM_CERTS_DIR` / `LCM_GRPC_PLAINTEXT=true` / `LCM_PXE_*`）；记录 PID
- 导出 `LCM_DEMO_SKIP_SATELLITE=1` 给 demo.sh
- 调用 `./scripts/demo.sh run`，把 stdout 同时写到 `$RUNTIME_DIR/demo-smoke.log`
- `trap EXIT` 统一清理：kill Core / Satellite PID，`docker-compose down` 由 demo.sh 的 `cleanup` 路径负责
- 失败时（exit code ≠ 0）把关键日志摘要打印到 stdout 便于 CI 日志定位，artifact 归档由 CI workflow 的 `upload-artifact` step 完成

**本地验证**：
- 有 Java 21 + Go 1.24 + Docker 的开发机依次跑：
  1. `./scripts/generate_keys.sh`
  2. `cd core && ./gradlew build -Dquarkus.package.type=fast-jar -x test`
  3. `cd satellite && go build -o satellite ./cmd/satellite`
  4. `./scripts/ci_demo_smoke.sh`
- 预期 exit 0，末尾打印 `print_summary` JSON

**自审 4 问**：
- 脚本退出时是否真的把 Core / Satellite 子进程都 kill 干净？
- 预启动 Core 的 env 变量是否和 load-test job 字段完全一致？
- 失败时 artifact 是否覆盖 Core stdout + Satellite stdout + demo.sh 日志 + mock redfish/ssh 日志 + WebSocket 事件日志？
- `$LCM_DEMO_CLUSTER` 未设时是否有合理默认（demo.sh 已有默认 `demo-lab-$(date +%s)`，驱动器不需要重新默认，但应在 CI 里显式覆盖为 `ci-smoke-${{ github.run_id }}`）？

**commit**：`feat(scripts): add ci_demo_smoke.sh to drive full-stack smoke in CI`

### Step 3 — CI workflow 新增 `demo-smoke` job

**修改**：[.github/workflows/ci.yml](../.github/workflows/ci.yml) 在 `load-test` job 之后插入 `demo-smoke`：

```yaml
demo-smoke:
  name: Demo Smoke (real backend)
  runs-on: ubuntu-latest
  needs: [ci-contract-guard, backend-test, satellite-build]
  timeout-minutes: 25

  steps:
    - uses: actions/checkout@v4

    - name: Set up JDK ${{ env.JAVA_VERSION }}
      uses: actions/setup-java@v4
      with:
        java-version: ${{ env.JAVA_VERSION }}
        distribution: 'temurin'
        cache: 'gradle'

    - name: Set up Go ${{ env.GO_VERSION }}
      uses: actions/setup-go@v5
      with:
        go-version: ${{ env.GO_VERSION }}
        cache-dependency-path: satellite/go.sum

    - name: Set up Python 3
      uses: actions/setup-python@v5
      with:
        python-version: '3.11'

    - name: Install CLI deps (grpcurl, websocat, jq, nc)
      run: |
        sudo apt-get update
        sudo apt-get install -y jq netcat-openbsd
        # grpcurl + websocat pinned versions
        ...

    - name: Generate dummy TLS keys
      run: chmod +x scripts/generate_keys.sh && ./scripts/generate_keys.sh

    - name: Build Core (fast-jar)
      working-directory: core
      run: ./gradlew build -Dquarkus.package.type=fast-jar -x test

    - name: Build Satellite binary
      working-directory: satellite
      run: go build -o satellite ./cmd/satellite

    - name: Run demo smoke
      run: ./scripts/ci_demo_smoke.sh
      env:
        LCM_DEMO_CLUSTER: ci-smoke-${{ github.run_id }}
        QUARKUS_OTEL_SDK_DISABLED: "true"

    - name: Upload smoke artifacts
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: demo-smoke-artifacts
        path: |
          /tmp/hyperscale-lcm-demo/
        retention-days: 7
```

**关键决策**：
- **不用** `services:` 块——demo.sh 自己会 `docker-compose up postgres redis kafka jaeger`，runner 本身已经装了 docker + docker-compose。本地与 CI 路径保持一致是 smoke test 的核心价值。
- `needs: [ci-contract-guard, backend-test, satellite-build]`：核心测试绿了才跑 smoke（节省 runner 预算）；不依赖 `load-test` 以便 smoke 和 load-test 可并行。
- `timeout-minutes: 25`：覆盖 gradle build (~3min) + docker-compose 启动 (~30s) + Core 启动 (~15s) + demo.sh 完整链路 (~3-5min)，留充足缓冲。对 smoke 主动加 timeout 是因为它涉及真实网络 / 容器操作，挂起风险比 unit test 高。
- `LCM_DEMO_CLUSTER: ci-smoke-${{ github.run_id }}`：并发 run 隔离。
- `QUARKUS_OTEL_SDK_DISABLED: "true"`：和 load-test 一致，避免 OTel exporter `localhost:4317 Connection refused` 噪音（[CLAUDE.md](../CLAUDE.md) 明确列为已知噪音）。
- `if: always()` 上传 artifact，失败时必有日志。

**grpcurl / websocat 安装**（CLI 工具固定版本）：
- `grpcurl`：v1.9.1 官方 release tarball
- `websocat`：v1.13.0 官方 musl 静态二进制

**本地验证**：
- `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"` 确认 yaml 合法
- `./scripts/check_ci_contract.sh` 不应报 regress（除非 guard 做 workflow job 白名单校验，则进 Step 4）

**自审 4 问**：
- [CI_CONTRACT.md](CI_CONTRACT.md) 第 2 节 "高风险改动范围" 明确列出 `.github/workflows/**` → 执行 Step 3 前必读一遍，确认没有被现有 guard 规则拦住
- `needs` 是否会导致 PR 上 smoke job 被跳过？（不会，`backend-test` 和 `satellite-build` 都已经在 PR 上跑）
- 总 CI 时长：smoke ≈ 10-12 min，和 load-test（~8 min）并行跑不会拉长 critical path（critical path 仍然是 backend-test ~15 min）
- 是否新增了任何 PR 作者需要手动补 secret 的步骤？（没有，smoke 用 demo 硬编码凭据）

**commit**：`ci: add demo-smoke job covering full real-backend smoke via demo.sh`

### Step 4 — `scripts/check_ci_contract.sh` guard 同步（条件执行）

**前置阅读**：[scripts/check_ci_contract.sh](../scripts/check_ci_contract.sh) + 它调用的 `check_quarkus_ci_contract.sh` / `check_load_test_contract.sh`，看 guard 是否对 workflow job 列表或字段做白名单校验。

**处理策略**：
- 若 guard 只做字段级断言（例如 "load-test job 必须包含 `QUARKUS_OTEL_SDK_DISABLED`"），新增 `demo-smoke` job 不会被 guard 拦住，本 Step 跳过
- 若 guard 对 workflow job 数量 / 名称做白名单校验，同步更新断言集合
- 若 guard 对 `docker-compose*.yml` 做字段校验（CI_CONTRACT.md 2.4 把 `docker-compose*.yml` 列为高风险），确认本 Phase 未触碰 `docker-compose.yml` 文件本身（仅通过 demo.sh 调用）

**自审 4 问**：
- guard 原本校验什么？（必须先读再改）
- 新增 `demo-smoke` 是否会让 guard 把它判成未知 job？
- commit 前跑 `./scripts/check_ci_contract.sh` 是否绿？
- 若跳过此 Step，是否在 PR 描述里声明原因？

**commit**：`chore: update ci_contract guard for demo-smoke job`（若无需修改则跳过）

### Step 5 — PROJECT_STATUS.md 状态刷新

**修改**：[PROJECT_STATUS.md](PROJECT_STATUS.md)

- 顶部 `Last Updated` 刷到当日
- 1.1 "集成测试" 行：措辞**诚实化**为 "Playwright E2E（API mock 级）覆盖主流程；`demo-smoke` 在 CI 覆盖真实 Core+Satellite+gRPC+Kafka+WebSocket 端到端链路；断连恢复和压力级故障场景仍不充分"
- 1.3 "Demo 脚本" 行：🟡 → ✅，备注改为 "`scripts/demo.sh` 本地闭环 + CI `demo-smoke` job 真实后端门禁"
- 1.3 "Playwright 浏览器级回归" 行：保持 ✅，但在备注里明确 "API mock 级；真实后端链路由 `demo-smoke` job 覆盖"，避免误读
- 第 3 节 P1 行移除 "Demo smoke 扩展（与真实后端链路的端到端冒烟）"
- 第 3 节"建议执行顺序"近期部分改为 "AlertManager 真实 channel 冒烟验证 + 真实硬件 Redfish/BMC 验收数据填充"
- 追加链接到本份 `DEMO_SMOKE_PHASE_PLAN.md`

**自审 4 问**：
- 措辞是否避免了新的夸大（例如 "全部端到端覆盖"）？
- 是否引用了 `demo-smoke` job 的名字以便未来搜索定位？
- commit 是否只触碰 PROJECT_STATUS，避免串 scope？
- 是否忘记刷 `Last Updated`？

**commit**：`docs: mark Phase Demo Smoke productionization landed`

### Step 6 — 完整回归 + PR 提交

在推送 PR 前本地依次跑：

1. `./scripts/check_ci_contract.sh`
2. 开发机完整跑一次 `./scripts/ci_demo_smoke.sh`（需要 Docker daemon + Java 21 + Go 1.24），预期 exit 0 且末尾打印 `print_summary` JSON
3. `cd core && ./gradlew check --no-daemon`（Core 生产代码未改，但 guard 回归）
4. `cd satellite && go test ./... -count=1`（Satellite 生产代码未改，但 guard 回归）
5. `cd frontend && npm test && npm run build`（前端完全未触碰，但 [CLAUDE.md](../CLAUDE.md) 要求批量改动三子系统都要回归）
6. 开 PR，`gh pr merge --squash` 风格；等新增 `demo-smoke` job 连续 2-3 次绿灯后再合并，观察 flaky 行为

**commit** 的时机：每个 Step 完成且自审通过后**立即 commit 并推送到 feature 分支**，不攒堆。CI 会在每次 push 上跑，早暴露问题。

## Public Interfaces

- **新增 CI job**：`demo-smoke`，依赖 `ci-contract-guard`, `backend-test`, `satellite-build`
- **新增环境变量**：`LCM_DEMO_SKIP_SATELLITE`（demo.sh 识别，非空即跳过 `start_satellite`；**默认不设**，对所有本地用户保持兼容）
- **新增 CI 工件**：`demo-smoke-artifacts`，含 `/tmp/hyperscale-lcm-demo/*.log`（Core / Satellite / demo / mock-redfish / mock-ssh / WebSocket 事件），保留 7 天
- **显式不变**：Core / Satellite / Frontend 生产代码、Helm chart、`lcm.proto`、JaCoCo 门禁、load-test job 逻辑、Playwright 配置 / fixtures / spec、AlertManager 相关文件

## Test Plan

| 层 | 命令 | 新增 / 存量 |
|---|---|---|
| demo.sh 默认路径 | `./scripts/demo.sh run`（不传新变量） | 存量回归 |
| demo.sh skip 路径 | `LCM_DEMO_SKIP_SATELLITE=1 ./scripts/demo.sh cleanup` | 新增（快速确认 flag 不破坏 cleanup）|
| CI smoke 驱动器 | `./scripts/ci_demo_smoke.sh`（开发机） | 新增 |
| Core | `cd core && ./gradlew check --no-daemon` | 存量回归 |
| Satellite | `cd satellite && go test ./... -count=1` | 存量回归 |
| Frontend | `cd frontend && npm test && npm run build` | 存量回归 |
| CI contract guard | `./scripts/check_ci_contract.sh` | 存量回归（必要时同步更新） |
| GitHub Actions | `demo-smoke` job PR 首跑 + 连续 2-3 次绿灯 | 新增 |

**不在本 Phase 的验证项**：
- 多 Redfish 固件矩阵（仅跑 `openbmc-baseline`，其它 `dell-idrac` / `hpe-ilo` / `lenovo-xcc` 由 demo.sh 支持但本 Phase 不进 CI）
- 失败恢复 / 断连场景（留给后续 phase）
- 性能 / 延迟断言（`demo-smoke` 只做功能性门禁，不做阈值）
- 真实 Slack/PagerDuty/邮件 AlertManager 通道（仍由 Phase AlertManager runbook 覆盖）

## Implementation Notes（占位，留给执行者回填）

- **grpcurl / websocat 下载稳定性**：若 GitHub Release 404，fallback 到 `apt` 或 `go install github.com/fullstorydev/grpcurl/cmd/grpcurl`
- **docker-compose v1 vs v2 兼容**：demo.sh 当前硬编码 `docker-compose`（v1）；若 CI runner 只有 `docker compose`（v2），需给 demo.sh 加兼容层 —— 优先不改 demo.sh，确认 `ubuntu-latest` runner 预装了哪种
- **Core `/health/ready` 超时**：demo.sh 默认 `wait_for_http` 是 120s；CI 冷启动可能更慢，必要时在驱动器里把超时拉到 180s
- **mock-ssh 首次 `go run` 冷启动**：本地 ~5s，CI 首次可能 ~15s；超时默认 30s 应够
- **docker-compose 服务清理**：`trap EXIT` 里 kill Core/Satellite 后是否还要显式 `docker-compose down`？demo.sh 自身 `cleanup` 会做，但异常退出路径要确认

## Assumptions

- GitHub Actions `ubuntu-latest` runner 预装 `docker` + `docker-compose`（v2 `docker compose` 或 v1 `docker-compose`）。若两者都不存在，Step 3 的 Install CLI deps step 需要补装。首跑前必须用 `which docker-compose` / `docker compose version` 确认。
- `grpcurl` v1.9.1 和 `websocat` v1.13.0 的 GitHub Release tarball / 静态二进制下载地址稳定。
- `scripts/demo/mock_ssh_server.go` 首次 `go run` 冷启动时间 <30s。
- demo.sh 场景逻辑在开发机上**多次稳定通过** —— 如果开发机本身不稳定，应先修稳再开这个 Phase。
- `demo-smoke` job 总耗时 ≤15min（critical path 舒适上限；若超出需要评估并行化 build 步骤）。
- [CI_CONTRACT.md](CI_CONTRACT.md) 列出的 "高风险改动范围" 包括 `.github/workflows/**`，本 Phase 触碰该路径 → 必须走完本文 Test Plan 的完整验证矩阵。

## 关键文件清单（Phase 执行阶段要触碰的文件）

**新增**：
- [documentation/DEMO_SMOKE_PHASE_PLAN.md](DEMO_SMOKE_PHASE_PLAN.md)（本文件，Step 0 产出）
- `scripts/ci_demo_smoke.sh`（Step 2）

**修改**：
- [scripts/demo.sh](../scripts/demo.sh) — `start_satellite` 起始位置追加 `LCM_DEMO_SKIP_SATELLITE` 短路 + `usage()` 同步（Step 1）
- [.github/workflows/ci.yml](../.github/workflows/ci.yml) — 新增 `demo-smoke` job（Step 3）
- [scripts/check_ci_contract.sh](../scripts/check_ci_contract.sh) 或其被调度脚本 — 仅在 guard 需要同步时（Step 4，条件）
- [documentation/PROJECT_STATUS.md](PROJECT_STATUS.md) — 状态刷新 + Last Updated（Step 5）

**不得触碰**：
- Core 所有 Java 代码、`application*.properties`
- Satellite 所有 Go 代码（除 Step 2 的 `go build` 命令本身）
- Frontend 所有 TS/React 代码
- Playwright 配置、fixtures、spec（PR #21 刚落地，本 Phase 不叠加 scope）
- Helm chart、AlertManager 相关文件
- `lcm.proto` 与任何 gRPC 生成物
- JaCoCo 配置
- load-test job 逻辑、`backend-test` 的 `services:` 块

## 不做的事

- 不把 Playwright 切到真实后端（留作独立 follow-up phase）
- 不重写 demo.sh 的任何场景函数；仅在 `start_satellite` 前加一个非破坏性 skip 分支
- 不引入新的 smoke 断言框架（bats / shellspec 等）——demo.sh 自身的 `die` 失败即 CI 红
- 不把 `demo-smoke` 的产出 JSON 纳入 baseline 趋势比对（那是 load-test baseline 的 P2 方向，独立 phase）
- 不触碰 CI `services:` 块或 `backend-test` / `load-test` job 结构
- 不动 `docker-build` job 的触发条件（`if: github.ref == 'refs/heads/main'` 保持不变）
- 不在本 PR 内做 grpcurl / websocat 的版本升级或镜像迁移
- 不为 smoke job 引入 secret（用 demo 硬编码 `admin/admin123`，和本地体验一致）
- 不跑多 Redfish 固件矩阵（demo.sh 支持但本 Phase 只跑默认 `openbmc-baseline`）
