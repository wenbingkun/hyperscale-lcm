# 阶段八：Satellite session-aware 只读 + 真实硬件准入矩阵扩面

> Updated: 2026-04-15
> 由 Claude code 编写。
## Summary

- 阶段八继续聚焦 Redfish/BMC 专项，不扩散到 AlertManager 外部通道、Playwright 浏览器级 E2E、覆盖率提升或多集群联邦等其他主题——这些按需另起独立 plan。
- 本阶段定位为 Phase 7 的收尾与扩面，主线固定为两条：
  1. **Satellite 侧 session-aware 只读采集**：兑现 [REDFISH_BMC_PHASE7_PLAN.md](REDFISH_BMC_PHASE7_PLAN.md) 中明确延后的 P1 项，让 Satellite 与 Core 共享同一套 session 协议路径。
  2. **真实硬件准入矩阵扩面**：在 [hardware-acceptance/](hardware-acceptance/) 中为 Dell iDRAC / HPE iLO / Lenovo XCC 建立 per-machine markdown 骨架与 `pending:` 追踪段，让后续真实实验台一就位即可直接填数据。
- Phase 8 不新增也不修改任何 REST / gRPC 契约，继续冻结 `lcm.proto`，不扩展到 `EventService`、firmware update、virtual media、BIOS 配置、license / OEM inventory。
- Core 侧 `RedfishTransport + RedfishSessionManager`、`BmcManagementResource`、`application.properties` 中的 `lcm.claim.redfish.*` 配置键均**不在本阶段修改**。

## Key Changes

- **Step A — Satellite session-aware Redfish transport（Go 侧）**
  - 在 `satellite/pkg/redfish/` 新增 `transport.go` 与 `session.go`，对齐 Core 侧 [RedfishSessionManager.java](../core/src/main/java/com/sc/lcm/core/service/RedfishSessionManager.java) 与 `RedfishTransport`：
    - 承接所有只读 `GET` 请求；中途 `401` 自动重建 session 并重放一次；TTL 取 `min(BMC SessionTimeout, LCM_BMC_SESSION_TTL_SECONDS_MAX)`；若 BMC 未返回 timeout，则使用配置上限。
    - session 仅缓存在进程内；adapter 关闭或缓存淘汰时对已知 `sessionUri` 做 best-effort `DELETE /Sessions/{id}`。
    - 缓存 key 与 Core 行为一致：`(normalizedEndpoint, normalizedUsername, sha256(password), insecure)`；同一 key 的并发请求共享同一次建会话，避免 session 风暴。
  - 认证模式由新增环境变量 `LCM_BMC_AUTH_MODE` 控制，支持 `BASIC_ONLY | SESSION_PREFERRED | SESSION_ONLY`，默认 `SESSION_PREFERRED`，与 Core 端 `lcm.claim.redfish.auth-mode-default` 的默认值保持一致。
  - 改造现有采集路径：
    - [adapter.go](../satellite/pkg/redfish/adapter.go) 的 `getJSON` / `fetchFingerprint` / `fetchPrimaryResource` 全部走统一 `transport.Do(ctx, req)`。
    - [template_adapter.go](../satellite/pkg/redfish/template_adapter.go) 中所有 `req.SetBasicAuth(...)` 调用移除，改由 transport 按模式注入 `Authorization: Basic` 或 `X-Auth-Token`。
    - 仅覆盖只读路径（`CollectStaticInfo` / `CollectDynamicTelemetry` / `fetchFingerprint`）——**Satellite 本阶段不引入任何写操作**；破坏性 `power-actions` 继续只允许通过 Core 侧 `/api/bmc/devices/{id}/power-actions`。
  - 失败语义固定为：
    - `SESSION_ONLY` 命中不支持 session 的设备：记录明确错误码到日志并让采集直接失败，不自动降级为 Basic。
    - `SESSION_PREFERRED` 在 session 创建失败或响应缺 `X-Auth-Token` 时：回退到 Basic，并打一条 `info` 级日志，不把临时 fallback 固化为设备级 override。
    - `BASIC_ONLY`：完全保留今天的行为，等价于当前 `req.SetBasicAuth(...)` 调用。
- **Step B — Satellite fixture 升级**
  - 为下列四份 vendor fixture 增加 `SessionService/Sessions` 片段，确保 fixture 同时支持 `BASIC_ONLY` 与 `SESSION_PREFERRED` 两种模式：
    - [openbmc-baseline.json](../satellite/pkg/redfish/testdata/vendor-fixtures/openbmc-baseline.json)
    - [dell-idrac.json](../satellite/pkg/redfish/testdata/vendor-fixtures/dell-idrac.json)
    - [hpe-ilo.json](../satellite/pkg/redfish/testdata/vendor-fixtures/hpe-ilo.json)
    - [lenovo-xcc.json](../satellite/pkg/redfish/testdata/vendor-fixtures/lenovo-xcc.json)
  - 扩充 [vendor_fixture_test.go](../satellite/pkg/redfish/vendor_fixture_test.go)：table-driven 跑四个 vendor × 两种认证模式，断言型号 / 电源 / 温度字段在两种模式下都能被正确采集。
  - 新增 `session_test.go`，覆盖 session 协议路径下的关键语义：
    - session 成功建立、缓存复用、TTL 过期后自动重建；
    - 中途 401 触发一次重建并重放；
    - `SESSION_ONLY` 命中无 session 设备时返回明确错误；
    - `SESSION_PREFERRED` 在 session 创建成功但无 `X-Auth-Token` 时回退到 Basic；
    - adapter 关闭或缓存淘汰时触发 best-effort `DELETE`。
- **Step C — 真实硬件准入矩阵扩面**
  - 沿用 [openbmc-reference.md](hardware-acceptance/openbmc-reference.md) 的章节结构（Identity / Auth modes / Claim & rotate / Power actions / Observed quirks / Mock fixture link），新增三份 per-machine markdown 骨架：
    - `documentation/hardware-acceptance/dell-idrac-reference.md`
    - `documentation/hardware-acceptance/hpe-ilo-reference.md`
    - `documentation/hardware-acceptance/lenovo-xcc-reference.md`
  - 三份骨架的字段以 `TBD` 占位；`Mock fixture link` 分别指向对应的 `satellite/pkg/redfish/testdata/vendor-fixtures/*.json`，这样验收人能在同一个 PR 里对齐 fixture 与真实设备观察。
  - [hardware-acceptance/matrix.yaml](hardware-acceptance/matrix.yaml) 继续保留 `machines: []`（真实验证仍需实验台数据），但在 schema 下方新增 `pending:` 段，记录三个机型及 owner TBD，让后续填数据的人一眼能看到谁欠验收。
  - **不触碰 Core 侧 `RedfishMockServer` 的已有 fixture 方法名或签名**；如后续需要扩 session-capable fixture 片段，只允许追加新方法，不允许改旧方法。
- **Step D — 配置与运维同步**
  - [satellite/pkg/redfish/config.go](../satellite/pkg/redfish/config.go) 的 `loadConfigFromEnv` 增补两个字段：
    - `LCM_BMC_AUTH_MODE`（默认 `SESSION_PREFERRED`）
    - `LCM_BMC_SESSION_TTL_SECONDS_MAX`（默认 `1800`，与 Core 的 `lcm.claim.redfish.session-ttl-seconds-max` 一致）
  - [helm/hyperscale-lcm/values.yaml](../helm/hyperscale-lcm/values.yaml) 的 satellite 段追加对应占位。
  - [PROJECT_STATUS.md](PROJECT_STATUS.md) 第 1.2 节的"Redfish / BMC Phase 7 深化"行同步"Phase 8 已规划"；第 3 节 P1 行移除"延后自 Phase 7"字样；顶部 `Last Updated` 刷新。

## Public Interfaces

- **无新增/变更 REST 或 gRPC 接口。**
- Core 端 `/api/bmc/devices/{id}/{capabilities,claim,rotate-credentials,power-actions}` 契约保持不变。
- `lcm.proto`、`DiscoveredDevice` schema、前端路由、RBAC 角色矩阵均不受影响。
- 新增环境变量（仅 Satellite 侧）：
  - `LCM_BMC_AUTH_MODE=SESSION_PREFERRED`
  - `LCM_BMC_SESSION_TTL_SECONDS_MAX=1800`
- 明确不变：
  - 不新增 `EventService`、firmware、virtual media、BIOS、license / OEM inventory 接口
  - 不修改 Core 端任何 `lcm.claim.redfish.*` 配置键
  - 不修改 `core/build.gradle` 的 JaCoCo 配置

## Test Plan

- **Satellite (`cd satellite && go test ./... -count=1`)**
  - `TestSessionManagerReuseAcrossRequests` — 同一 key 的多次请求共享 session，不触发重复建会话。
  - `TestSessionExpiryTriggersRebuild` — TTL 过期后自动新建 session。
  - `TestSessionUnauthorizedRebuildsAndReplays` — 中途 401 触发一次重建并重放原请求。
  - `TestSessionOnlyFailsWhenSessionUnsupported` — `SESSION_ONLY` 命中无 session 设备时返回明确错误，不降级为 Basic。
  - `TestSessionPreferredFallsBackOnMissingToken` — `SESSION_PREFERRED` 在无 `X-Auth-Token` 时回退到 Basic，并打 info 级日志。
  - `TestSessionBestEffortDeleteOnClose` — adapter 关闭或缓存淘汰时对已知 `sessionUri` 触发 `DELETE`。
  - `TestVendorFixturesBasicAndSessionModes` — table-driven，四个 vendor × 两种认证模式。
- **Core (`cd core && ./gradlew check --no-daemon`)**
  - 不新增测试用例；仅验证 Phase 8 的 Satellite/文档改动不回归 Phase 7 套件（`BmcManagementResourceTest`、`RedfishTransport*`、`RedfishSessionManager*`、`RedfishMockServer*`、`E2EIntegrationTest`）。
- **Hardware acceptance**
  - PR 级检查：三份 per-machine markdown 必须符合 [hardware-acceptance/README.md](hardware-acceptance/README.md) 的 "Reporting workflow" 骨架。
  - `matrix.yaml` 的 `pending:` 段必须同时列出 `dell-idrac-reference`、`hpe-ilo-reference`、`lenovo-xcc-reference` 三个条目。
  - 真实验收数据暂不在 Phase 8 交付范围——Phase 8 只提供骨架与 `pending:` 追踪；真实硬件跑通后以独立 PR 把行追加到 `machines:`。
- **CI guard**
  - 触及 `satellite/**` 即按 [CI_CONTRACT.md](CI_CONTRACT.md) 执行 `./scripts/check_ci_contract.sh`；Satellite 代码改动后必须额外跑 `go test ./... -count=1`。
  - 不触及 Core / frontend / workflows / application\*.properties / `load-test`，因此 Core 与 frontend 的完整验证矩阵在本阶段无强制要求，但建议合入前至少跑一次 `cd core && ./gradlew check --no-daemon` 做冒烟回归。

## Implementation Notes

- _Satellite transport/session_ — 待执行者在落地 Step A/B 后按 Phase 7 惯例回填。
- _Fixture & regression_ — 待回填。
- _Hardware matrix pending tracker_ — 待回填。

## Assumptions

- Satellite 真实 BMC 池仍可能不完整；因此 Phase 8 代码 + fixture 可以先闭环，真实硬件数据不是 blocker。
- OEM 差异继续通过模板 + capability profile 处理；只有标准路径无法覆盖时才允许最小 OEM hook。
- Phase 8 仍不引入 `EventService`、firmware、virtual media、BIOS、license / OEM inventory，也不引入 AlertManager 外部通道或多集群联邦——这些是独立 P0/P1 工作流的范围。
- destructive `power-actions` 的真实硬件验收继续只允许在维护窗口或专用样机执行，且只能通过 Core 侧入口发起；Satellite 本阶段不获得任何写能力。

---

## Codex Appendix — Implementation Draft (2026-04-15)

> 由 Codex 编写。
> 本节为对上文 Phase 8 原计划的追加实现附录，不替换、不修改原计划正文。

### Summary

- 本附录基于仓库现状补充 Phase 8 的落地拆解，重点是让 Satellite 的只读 Redfish 采集切到统一 `session-aware transport`，并补齐真实硬件准入骨架。
- 不变更任何 REST、gRPC、`lcm.proto`、前端路由、RBAC 或 Core 侧 `lcm.claim.redfish.*` 配置键。
- 已确认当前基线可用：`cd satellite && go test ./pkg/redfish/... -count=1` 通过。

### Codex Decisions

- Satellite 运行时默认使用 template 路径，而不是继续依赖 `OpenBMCAdapter` 作为主路径。
- Redfish templates 采用“镜像内置”方案，不修改 CI workflow 的 Satellite build context。
- 为避免改动 `.github/workflows/ci.yml`，运行时模板资产放在 `satellite/` 目录内维护，并在镜像构建时复制到 `/app/redfish-templates`。
- `LCM_REDFISH_TEMPLATE_DIR` 未显式配置时，默认取 `/app/redfish-templates`；若显式配置，则以用户值为准。
- `OpenBMCAdapter` 仅保留为模板目录不可用时的兼容回退，不作为正常默认采集路径。

### Implementation Additions

- 在 `satellite/pkg/redfish/` 新增 `transport.go` 与 `session.go`，统一承接只读 `GET`、Basic/Session 认证注入、TTL 计算、401 重建重放、session 缓存与 best-effort `DELETE`。
- 改造 `adapter.go` 与 `template_adapter.go`，移除分散的 `SetBasicAuth(...)` 和裸 `HTTPClient().Do(...)` 路径，统一改走 transport。
- 扩展 `Config`，新增 `LCM_BMC_AUTH_MODE` 与 `LCM_BMC_SESSION_TTL_SECONDS_MAX`，默认值分别为 `SESSION_PREFERRED` 与 `1800`。
- 为 Collector 增加关闭清理能力，并在 `satellite/cmd/satellite/main.go` 的优雅退出路径触发 session cleanup。
- 在 `satellite/` 下新增运行时 `redfish-templates/` 副本，并增加测试约束，保证其与 `documentation/redfish-templates/` 不漂移。
- 升级四份 vendor fixture，补齐 `SessionService/Sessions` 片段，使其同时覆盖 `BASIC_ONLY` 与 `SESSION_PREFERRED`。
- 新增三份硬件准入 markdown 骨架，并在 `documentation/hardware-acceptance/matrix.yaml` 新增 `pending:` 三条待验收记录。

### Verification

- 必跑：`./scripts/check_ci_contract.sh`
- 必跑：`cd satellite && go test ./... -count=1`
- 新增测试覆盖 session 复用、TTL 过期、401 重建、`SESSION_ONLY` 明确失败、`SESSION_PREFERRED` 缺 token 回退、best-effort delete、四 vendor × 两认证模式回归。
- 建议补跑一次 `cd core && ./gradlew check --no-daemon` 作为 Phase 7 回归冒烟，但不作为本阶段 Core 改动验证矩阵的一部分。

### Assumptions

- 无效的 `LCM_BMC_AUTH_MODE` 或 TTL 配置不应阻塞 Satellite 启动，应回退到默认值并记录 warning。
- `SESSION_PREFERRED` 的 fallback 只在运行时生效，不固化为设备级 override。
- 本阶段不顺带移除 `gofish` 依赖，只降低其运行时重要性，避免扩大改动面。
- 真实硬件数据仍可后补；Phase 8 当前交付以代码、fixture、文档骨架闭环为准。

---

## Claude Review — Appendix Critique (2026-04-15)

> 由 Claude code 编写。
> 本节是对上文 Codex Appendix 的评审意见，不修改原计划正文，也不改动 Codex 附录本身。

### Scope

- 针对 Codex Appendix 的 Decisions / Implementation Additions / Verification / Assumptions 各节做事实核对与方案评估。
- 仍然恪守原计划边界：不引入 AlertManager、Playwright、多集群联邦、EventService、firmware / virtual media / BIOS / license / OEM inventory。

### 事实核对（基于当前 HEAD 的仓库状态）

- ✅ [satellite/cmd/satellite/main.go](../satellite/cmd/satellite/main.go) 存在，已有优雅退出信号处理，但**没有**任何 Redfish collector 级别的 close 钩子。
- ✅ [documentation/redfish-templates/](redfish-templates/) 确实是当前模板的单一事实来源，包含 `openbmc-baseline.json` / `dell-idrac.json` / `hpe-ilo.json` / `lenovo-xcc.json` 四份 JSON。
- ❌ `satellite/redfish-templates/` **目前不存在**；Codex 附录里的"镜像内置副本"是**新增目录**，不是已有事实。
- ✅ [satellite/pkg/redfish/openbmc_adapter.go](../satellite/pkg/redfish/openbmc_adapter.go) 仍然 `import "github.com/stmcginnis/gofish"`，`CollectStaticInfo` / `CollectDynamicTelemetry` 直接调 `gofish.Connect` 自己发 HTTP。
- ✅ 全仓 `grep -R gofish satellite/` 只命中 `openbmc_adapter.go` / `go.mod` / `go.sum` / README，**这是 `gofish` 的唯一使用点**。
- ✅ [.github/workflows/](../.github/workflows/) 无任何一行提到 `redfish-templates`；[satellite/Dockerfile](../satellite/Dockerfile) 也不 COPY 模板目录。"不改 ci.yml" 不是硬约束。
- ✅ [vendor_fixture_test.go:114](../satellite/pkg/redfish/vendor_fixture_test.go#L114) 中 `loadDocumentationTemplates` 通过 `..../..../documentation/redfish-templates` 相对路径读取模板；[vendor_fixture_test.go:156-174](../satellite/pkg/redfish/vendor_fixture_test.go#L156-L174) 的 `newFixtureTLSServer` 把 `Authorization: Basic ...` 硬编码进 handler，**没有 `X-Auth-Token` 识别**，也没有 `POST /redfish/v1/SessionService/Sessions` 路径。
- ✅ [satellite/pkg/redfish/config.go:23-40](../satellite/pkg/redfish/config.go#L23-L40) 的 `loadConfigFromEnv` 当前 `LCM_REDFISH_TEMPLATE_DIR` 读到空值时返回空字符串，`AdapterRegistry.Build` 落到 `NewOpenBMCAdapter`——也就是说**今天默认路径就是 OpenBMCAdapter**。

### 合理且应采纳的点

- **Codex 抓到了原计划的一个真实认知断层**：`OpenBMCAdapter` 绕过新 transport。由于 `LCM_REDFISH_TEMPLATE_DIR` 在开发与常见容器环境下为空，默认落到 `OpenBMCAdapter`，而它用 `gofish` 自管 HTTP——意味着 `LCM_BMC_AUTH_MODE=SESSION_*` 在默认路径上会被**静默忽略**。这是 Phase 8 合入后的真实隐患，必须在计划里处理。
- **`main.go` 级别的 session cleanup 钩子**应采纳。原计划只说"adapter 关闭或缓存淘汰时 best-effort DELETE"，没讲谁调用 `Close()`。在现有 [satellite/cmd/satellite/main.go:59](../satellite/cmd/satellite/main.go#L59) 的 `sigChan` 路径上挂 `collector.Close()` 是正确位置。

### 有争议的点

1. **"Template 变默认 + 镜像内置 `satellite/redfish-templates/` + drift test"是 scope creep**
   - Codex 给的理由"避免改动 `.github/workflows/ci.yml`"**不成立**：ci.yml 根本不引用 `redfish-templates`，Dockerfile 也不 COPY 模板。
   - 代价：
     - 两套模板副本（`documentation/redfish-templates/` + `satellite/redfish-templates/`）→ 必须写 drift test → 每次改模板都要改两边。
     - Dockerfile 需新增 `COPY redfish-templates/ /app/redfish-templates`，新增一条维护面。
     - `LCM_REDFISH_TEMPLATE_DIR` 默认 `/app/redfish-templates` 只在容器镜像里成立；`go run ./cmd/satellite` 开发流下这个目录不存在，会静默落回 `OpenBMCAdapter`——**dev 与 prod 默认行为分叉**，与 Codex 声称的"Template 是默认"自相矛盾。
2. **"本阶段不顺带移除 `gofish`，只降低其运行时重要性"在逻辑上不自洽**
   - 若 Template 变默认、`OpenBMCAdapter` 只做兜底 → `gofish` 在生产路径是死代码。
   - 但 `OpenBMCAdapter` 依然不 session-aware → 兜底路径在 `SESSION_PREFERRED` / `SESSION_ONLY` 下会悄悄降级为 gofish 的 Basic。
   - 结果：两套路径两种行为，`SESSION_ONLY` 的契约在兜底路径上被违反而无人察觉。
   - 要么**一起改掉 `OpenBMCAdapter`**，要么**在启动时显式拒绝"SESSION_* × OpenBMCAdapter 兜底"组合**，二选一；Codex 现方案两头都没做。
3. **fixture 升级方案不具体**
   - Codex 说"四份 vendor fixture 补齐 `SessionService/Sessions` 片段"，但现状 [newFixtureTLSServer:156-174](../satellite/pkg/redfish/vendor_fixture_test.go#L156-L174) 是按 URL path 查表 + Basic 校验硬编码的 handler。要真正支持 session 模式回归，必须：
     - `newFixtureTLSServer` 增加识别 `X-Auth-Token` 的分支；
     - handler 处理 `POST /redfish/v1/SessionService/Sessions`，生成 token 并通过响应 header 返回 `X-Auth-Token` + `Location`；
     - 增加模式开关让同一份 handler 可分别伪装 `BASIC_ONLY` / `SESSION_PREFERRED` / `SESSION_ONLY` / `session-unsupported`。
   - Codex 附录没写清这一层，直接按现文动手会卡在 fixture 测试一步。
4. **`LCM_REDFISH_TEMPLATE_DIR` 默认值回退语义未定义**
   - 若默认取 `/app/redfish-templates` 但目录不存在，`LoadTemplates` 返回 error，当前代码会落回 `NewOpenBMCAdapter`——即静默降级。Codex 附录未就这种情况给出期望行为（是 fail-fast？还是 warn + 降级？），必须明示。

### 缺口

- 未明确"Template × 全部 vendor 都存在有效 Redfish template"这一前提。若某个用户把 `LCM_REDFISH_TEMPLATE_NAME` 指向不存在的模板，现在会 `return nil, fmt.Errorf("template %q not found ...")`（[adapter.go:68-70](../satellite/pkg/redfish/adapter.go#L68-L70)）——Codex 没讨论这个错误路径在 Phase 8 下的用户体验是否需要调整。
- 未讨论 `TestSessionBestEffortDeleteOnClose` 与 main.go 关闭钩子之间的契约边界：是 collector 负责 DELETE 还是 sessionManager 负责？推荐在 collector 层做，main.go 只调 `collector.Close()`。
- 未讨论 session cleanup 的超时预算。当 satellite 进程被强杀时，best-effort DELETE 最多等多久？建议固定为 2 秒或低于 `grpc` 优雅退出超时。

### 推荐替代方案（按优先级）

**Option A（强烈推荐）——干掉根因，不做 scope creep**

- 重写 [openbmc_adapter.go](../satellite/pkg/redfish/openbmc_adapter.go) 使它用新的 `transport.Do`，直接解析 `/redfish/v1/Systems` / `Managers` / `Chassis/*/Thermal`（字段路径在现有 template 里已有现成参考）。
- 顺带**彻底移除 `gofish` 依赖**（仓库里仅此一处使用，`go.mod` / `go.sum` 同步清掉）。
- `Template` 路径仍然"用户显式配置才启用"，**默认行为不变**。
- 不新增 `satellite/redfish-templates/`、不改 Dockerfile、不写 drift test、不改 `LCM_REDFISH_TEMPLATE_DIR` 默认值。
- 好处：单一事实来源、无副本、无 drift test、无 dev/prod 分叉、`LCM_BMC_AUTH_MODE` 在所有路径一致生效、依赖减少一条。
- 成本：约 60~80 行 Go 代码（现有 template_adapter 里已有 `extractStringPaths` 之类的工具函数可直接复用）。

**Option B（妥协方案）——保留现状但认知透明**

- 保留 `OpenBMCAdapter` 与 `gofish` 现状。
- 在 `NewCollector` / `AdapterRegistry.Build` 里增加一段校验：若 `auth-mode ∈ {SESSION_PREFERRED, SESSION_ONLY}` 且最终 adapter 为 `openbmc-baseline`，则打一条 `warn` 日志明确说明"session mode 在 openbmc-baseline 路径上不生效"。
- README 同步说明这条限制，并告诉用户："要启用 session-aware 采集，必须显式配置 `LCM_REDFISH_TEMPLATE_DIR`（或在 Option A 完成后自动生效）。"
- 好处：零改动成本、认知透明、不欺骗用户。
- 缺点：`SESSION_ONLY` 仍然是"名义上全栈 session"但兜底路径无法兑现；适合短期过渡，不宜作为 Phase 8 最终态。

**Option C（不推荐）——Codex 原附录的"Template as default + 镜像内置"**

- 仅作为兜底的兜底：若 Option A 因团队带宽不足无法在 Phase 8 落地、Option B 的告警又被认为不够强——**且**团队接受 drift test / dev 与 prod 默认行为分叉 / Dockerfile 改动——才可考虑。
- 采纳前必须补齐：dev mode 的 fallback 契约、`LCM_REDFISH_TEMPLATE_DIR` 目录不存在时的期望行为、drift test 的执行位置与失败策略。

### 对 Phase 8 文档正文的影响建议

- 正文（第 1~102 行）不需要大改；在 `Key Changes` 的 Step A 末尾补一句"`OpenBMCAdapter` 路径当前不走新 transport，见 Claude Review 的 Option A/B 决策"，用一句话把这条隐患指到本 Review 即可。
- Codex Appendix 保留原文不动（用户要求）。
- 本 Review 追加在文末作为第三视角的决策参考；执行者在动手前应当**先在 Option A/B/C 之间明确选型**，再按所选方案落地。

### 结论

- Codex 捕获了一个重要的现状隐患（`OpenBMCAdapter` 绕过 transport），这一点比原计划细致；
- 但 Codex 给出的解法（Template 默认 + 镜像内置 + drift test）是 scope creep 且自相矛盾；
- **建议按 Option A 落地**：重写 `OpenBMCAdapter` 走新 transport、删除 `gofish` 依赖。这比 Codex 方案改动面更小、行为更一致、维护成本更低。

---

## Codex Follow-up — Review Response (2026-04-15)

> 由 Codex 编写。
> 本节用于响应上文 Claude Review，不修改原计划正文，也不改动 Codex Appendix 或 Claude Review 的原文。

### Position Update

- 接受 Claude Review 对 `OpenBMCAdapter` 绕过新 transport 的判断；这是 Phase 8 落地前必须消除的真实隐患。
- 撤回 Codex Appendix 中“Template 变默认 + 镜像内置 `satellite/redfish-templates/` + drift test”的建议，不再将其作为首选实现方向。
- 认可 Claude Review 的 **Option A** 为当前推荐路径：
  - 重写 `OpenBMCAdapter`，使其复用新的 `transport.Do(...)` 与 session 语义；
  - 删除 `gofish` 依赖；
  - 保持 `documentation/redfish-templates/` 作为模板的单一事实来源；
  - 不新增 `satellite/redfish-templates/` 副本，不为此修改 Docker / CI build context。

### Execution Clarifications

- Step A 的实现必须显式覆盖默认 OpenBMC 路径，确保 `LCM_BMC_AUTH_MODE` 在“配置 template”与“未配置 template”两条路径上的行为一致。
- Step B 的 fixture 升级不能只补 JSON 片段；还必须同时补齐 test server 对 `POST /redfish/v1/SessionService/Sessions`、`X-Auth-Token`、session unsupported / missing token 场景的协议模拟。
- session cleanup 的职责边界建议固定为：`collector.Close()` 负责触发 session 清理，`main.go` 只在优雅退出时调用该钩子，不把 session 删除逻辑散落到更上层。

### Superseded Codex Suggestions

- 不再建议把 `LCM_REDFISH_TEMPLATE_DIR` 默认值改为 `/app/redfish-templates`。
- 不再建议通过“双目录 + drift test”解决默认路径一致性问题。
- 不再建议保留一个不支持 session-aware 语义的 `OpenBMCAdapter` 作为长期兜底方案。

---

## Execution Playbook — Option A Landing Plan (2026-04-15)

> 由 Claude code 编写。
> 本节是 Phase 8 在 Codex 确认采纳 Option A 后的详细落地步骤。全节为**单向追加**，不修改正文 / Codex Appendix / Claude Review 任一节。
> 执行者必须按步骤号顺序推进，**每完成一项都要在提交推送前走完"实现 → 测试 → 自审 → 提交"四步**。

### Ground Rules（所有 Step 共用）

1. **分支**：从最新 `main` 切出 `feat/phase8-satellite-session`，所有 Step 的提交落在这条分支上；最终以一个 PR 合入 `main`。
2. **顶层文档必须先进仓**：在任何代码改动之前，**先把 [REDFISH_BMC_PHASE8_PLAN.md](REDFISH_BMC_PHASE8_PLAN.md) 与 [PROJECT_STATUS.md](PROJECT_STATUS.md) 的本次规划改动作为一个独立提交**合入分支，保证后续每个代码 Step 都是"在一个已有计划上的落地"。
3. **提交粒度**：每个 Step 一个提交，**不合并提交**、**不 `--amend` 历史提交**；提交信息遵循 [PROJECT_STANDARDS.md](../PROJECT_STANDARDS.md) 的 Conventional Commits 与 Phase 7 `core:` / `feat(frontend):` 前缀风格。
4. **测试纪律**：
   - 每个 Step 结束前**必须**跑 `cd satellite && go test ./... -count=1`；
   - 涉及 fixture / 模板 / 签名变更的 Step 额外跑 `cd satellite && go vet ./...`；
   - 整个 Playbook 结束后、推送远端前，额外跑 `./scripts/check_ci_contract.sh` + `cd core && ./gradlew check --no-daemon`（Phase 7 回归冒烟）。
5. **禁止事项**：
   - 禁用 `git commit --no-verify` 跳过 pre-commit hook；
   - 禁用 `git push --force` 推送 `feat/phase8-satellite-session`；
   - 禁止在本分支上引入 AlertManager、Playwright、覆盖率提升、多集群联邦等非 Phase 8 主题的改动；
   - 禁止触碰 Core 侧 [RedfishTransport.java](../core/src/main/java/com/sc/lcm/core/service/RedfishTransport.java) / [RedfishSessionManager.java](../core/src/main/java/com/sc/lcm/core/service/RedfishSessionManager.java) / `BmcManagementResource` / `application.properties` 中 `lcm.claim.redfish.*` 键；
   - 禁止触碰前端、`lcm.proto`、`core/build.gradle` 的 JaCoCo 段。
6. **每步自审清单（四问）**：提交前必须自己回答四个问题并在提交信息正文或 PR 描述里留一行证据：
   1. 新代码是否只覆盖本 Step 声明的文件？
   2. 新测试是否覆盖了本 Step 的关键失败路径？
   3. 本 Step 是否保持了上一 Step 通过的所有测试继续通过？
   4. 本 Step 是否没有顺手做"trivial cleanup"之外的其他主题？

### Step 0 — 分支与基线（无代码改动）

- **目标**：把本次 Phase 8 规划文档落盘，创建工作分支。
- **动作**：
  1. 在 `main` 上新建分支：`git switch -c feat/phase8-satellite-session`
  2. 将 [REDFISH_BMC_PHASE8_PLAN.md](REDFISH_BMC_PHASE8_PLAN.md)（含原计划 + Codex Appendix + Claude Review + 本 Playbook）与 [PROJECT_STATUS.md](PROJECT_STATUS.md) 的三处编辑一同 `git add` 后提交。
- **测试**：
  - 无代码改动；仅做文档结构 sanity check：`grep -c '^## ' documentation/REDFISH_BMC_PHASE8_PLAN.md` 应 ≥ 6。
- **自审**：确认分支从最新 `main` 切出，`git status` 干净。
- **提交信息建议**：
  ```
  docs(phase8): land Redfish/BMC Phase 8 plan with Codex & Claude reviews

  Why: Lock the Option A decision (rewrite OpenBMCAdapter with session-aware
  transport, drop gofish) before any code lands so every subsequent commit
  has a single shared plan to reference.
  ```
- **Rollback**：`git reset --hard origin/main` 放弃分支即可，文档未污染主干。

### Step 1 — 新增 `transport.go` + `session.go`（纯新增，行为零变更）

- **目标**：落地 session-aware transport 与 session manager，但**暂不接入任何已有 adapter**，仅通过单元测试覆盖协议路径；合入后现有采集路径行为与今天完全一致。
- **新增文件**：
  - `satellite/pkg/redfish/transport.go`
  - `satellite/pkg/redfish/session.go`
  - `satellite/pkg/redfish/session_test.go`
- **不触碰**：`adapter.go` / `template_adapter.go` / `openbmc_adapter.go` / `client.go` / `config.go` / `vendor_fixture_test.go` / 任何 JSON fixture。
- **关键设计约束**：
  - `Transport.Do(ctx, req)` 是纯函数式依赖注入：它只拿到 `Config` 与 `*SessionManager` 实例，不读环境变量。
  - `SessionManager` key 与 Core 保持一致：`(normalizedEndpoint, normalizedUsername, sha256(password), insecure)`；同一 key 并发只建一次会话。
  - `Transport` 暴露 `Close(ctx)` 用于 best-effort `DELETE /Sessions/{id}`，限时 2 秒。
  - `AuthMode` 枚举在 `session.go` 里定义，未知值返回 `SESSION_PREFERRED` 并打 warn（但这次不写日志 sink，用 `log.Printf`）。
- **新增测试（必须全部绿）**：
  - `TestSessionManagerReuseAcrossRequests`
  - `TestSessionManagerConcurrentSingleBuild`（goroutine × N，断言 `builds == 1`）
  - `TestSessionExpiryTriggersRebuild`
  - `TestSessionUnauthorizedRebuildsAndReplays`
  - `TestSessionOnlyFailsWhenSessionUnsupported`
  - `TestSessionPreferredFallsBackOnMissingToken`
  - `TestSessionCloseDeletesKnownSessionsBestEffort`
  - `TestBasicOnlyNeverCreatesSession`
- **测试命令**：
  ```
  cd satellite && go test ./pkg/redfish/... -run 'TestSession|TestBasicOnly|TestTransport' -count=1 -race
  cd satellite && go test ./... -count=1
  cd satellite && go vet ./...
  ```
- **自审**：四问全答；额外确认无 import `gofish`；确认 `session_test.go` 使用 `httptest.Server` 独立构造协议场景，不复用 `newFixtureTLSServer`。
- **提交信息建议**：`feat(satellite): add session-aware Redfish transport core (unwired)`
- **Rollback**：`git revert HEAD`；因为不接入任何现有路径，回滚零副作用。

### Step 2 — 重写 `openbmc_adapter.go` 走新 transport，移除 `gofish`

- **目标**：把兜底路径的 `OpenBMCAdapter` 改为基于 Step 1 的 transport，实现"无 template 场景也能走 session-aware"；顺带彻底清掉 `gofish` 依赖。
- **修改文件**：
  - `satellite/pkg/redfish/openbmc_adapter.go` — 重写为通过 `Transport.Do` 直接 GET `/redfish/v1/Systems` / `/Managers` / `/Chassis`，解析逻辑可复用 `template_adapter.go` 现有的 `extractString` / `extractStringPaths` 工具。
  - `satellite/pkg/redfish/client.go` — `NewOpenBMCAdapter(config)` 构造参数增加 `Transport`（或 `SessionManager`），让 `NewCollector` 把依赖注入下去。
  - `satellite/pkg/redfish/adapter.go` — 同步修改 `Build()` 中构造 `NewOpenBMCAdapter` 的调用点。
  - `satellite/go.mod` / `satellite/go.sum` — 移除 `github.com/stmcginnis/gofish`（`go mod tidy` 应自动清理）。
- **不触碰**：Step 3 才动的 `template_adapter.go`；fixture JSON；`vendor_fixture_test.go`。
- **新增/修改测试**：
  - 在 `openbmc_adapter_test.go`（新文件）里加：
    - `TestOpenBMCAdapterCollectStaticInfoViaTransport` — 通过 `httptest.Server` 模拟 Redfish，断言 `SystemSerial` / `SystemModel` / `PowerState` / `BMCMAC` 字段正确提取。
    - `TestOpenBMCAdapterHonorsSessionPreferred` — 断言当 mock server 支持 session 时，adapter 携带 `X-Auth-Token`；不支持时回退 Basic。
    - `TestOpenBMCAdapterHonorsSessionOnlyFailure` — session-only × 不支持 session 的 mock，断言 `CollectStaticInfo` 返回明确错误。
- **测试命令**：
  ```
  cd satellite && go test ./pkg/redfish/... -count=1 -race
  cd satellite && go test ./... -count=1
  cd satellite && go mod tidy && git diff --quiet go.mod go.sum && echo OK
  cd satellite && go vet ./...
  grep -R 'github.com/stmcginnis/gofish' satellite && exit 1 || echo "gofish removed"
  ```
- **自审**：
  - 确认 `openbmc_adapter.go` 不再 `import "github.com/stmcginnis/gofish"`；
  - 确认 `go.mod` `require` 段不再列 `gofish`；
  - 确认 Step 1 的测试继续绿；
  - 确认无 "trivial cleanup" 副作用（比如顺手格式化无关文件）。
- **提交信息建议**：`feat(satellite): rewrite OpenBMCAdapter on session-aware transport, drop gofish`
- **Rollback**：`git revert HEAD` + 恢复 `gofish` 依赖；回滚后需重新 `go mod tidy`。

### Step 3 — 把 `template_adapter.go` / `adapter.go` 迁到新 transport

- **目标**：把现有 `TemplateAdapter` 的裸 `http.Client` + `SetBasicAuth` 路径统一走 Step 1 的 transport；让"配置了 template"与"未配置 template"在认证行为上对齐。
- **修改文件**：
  - `satellite/pkg/redfish/template_adapter.go` — `fetchPrimaryResource` / `fetchManagerEthernet` / `fetchChassisThermal`（若有）全部改走 `Transport.Do`；移除所有 `SetBasicAuth` 调用；构造器签名增加 `Transport` 依赖注入。
  - `satellite/pkg/redfish/adapter.go` — `AdapterRegistry` 持有一个共享的 `Transport` 实例（基于 `Config` 构造），`Build()` 把该实例透传给 `NewTemplateAdapter` / `NewOpenBMCAdapter`。
  - `satellite/pkg/redfish/client.go` — `NewCollector` 构造 `Transport` 并注入 registry；暴露 `(*Collector).Close()` 接口供 Step 5 调用。
- **不触碰**：`config.go`（Step 4 再动环境变量）；`openbmc_adapter.go`（Step 2 已就位）。
- **关键约束**：
  - **签名修改只能往构造器传参方向追加**，不允许破坏 `Adapter` 接口方法签名，保持 `Name() / CollectStaticInfo() / CollectDynamicTelemetry()` 不变。
  - 若发现 `template_adapter.go` 中有任何裸 `http.Client`/`SetBasicAuth` 调用遗漏，在本 Step 一次清理干净，**不允许拖到 Step 4 或之后**。
- **测试命令**：
  ```
  cd satellite && go test ./pkg/redfish/... -count=1 -race
  grep -Rn 'SetBasicAuth' satellite/pkg/redfish && exit 1 || echo "SetBasicAuth clean"
  cd satellite && go test ./... -count=1
  ```
- **自审**：
  - `grep -Rn 'http\.Client{' satellite/pkg/redfish` 应该只剩 `config.go` 里的一处用于 transport 底层构造；
  - 确认 Step 1 / Step 2 测试继续绿；
  - 确认 vendor fixture 的现有回归（`TestVendorTemplateFixtures`）仍然过 —— 现在它走的是 transport 的 BASIC 路径，但 fixture test server 还只懂 Basic，**所以必须依然是 BASIC_ONLY 默认**（Step 4 之前）。
- **提交信息建议**：`refactor(satellite): unify TemplateAdapter & registry on session-aware transport`
- **Rollback**：`git revert HEAD`；因为 Step 2 的 OpenBMCAdapter 构造签名已经带 `Transport` 参数，如果回滚 Step 3，需要检查 `adapter.go` `Build()` 是否还能找到注入点。

### Step 4 — 扩展 `Config` + 环境变量 + `NewCollector` 默认接线

- **目标**：把 `LCM_BMC_AUTH_MODE` 与 `LCM_BMC_SESSION_TTL_SECONDS_MAX` 接入 `Config`，让 `NewCollector` 构造的 `Transport` 读取这两个值；默认行为**切换为 `SESSION_PREFERRED`**（这是本 Playbook 里第一个用户可感知的行为变更）。
- **修改文件**：
  - `satellite/pkg/redfish/config.go` — `loadConfigFromEnv` 增加两个字段 + 默认值 + 非法值回退到默认并 warn。
  - `satellite/pkg/redfish/client.go` — `NewCollector` 将 `Config.AuthMode` / `Config.SessionTTLSecondsMax` 透传给 `Transport` 构造；mock 模式保持与今天完全一致（不受 env 影响）。
- **不触碰**：fixture JSON；`vendor_fixture_test.go` 还只懂 Basic，所以 Step 4 **必须同时**把 `TestVendorTemplateFixtures` 保护住 —— 方法是：在 `NewCollector` / `AdapterRegistry` 新增一条内部可见的构造函数 `newCollectorForTest(config, authMode)` 允许测试显式传 BASIC_ONLY。Step 5 才会把 fixture harness 升级到支持 session。
- **新增测试**：
  - `TestConfigDefaultsSessionPreferred`
  - `TestConfigInvalidAuthModeFallsBackWithWarning`
  - `TestConfigSessionTTLSecondsMaxCapping`
- **测试命令**：
  ```
  cd satellite && go test ./pkg/redfish/... -count=1 -race
  cd satellite && go test ./... -count=1
  cd satellite && go vet ./...
  ```
- **自审**：
  - 确认 `TestVendorTemplateFixtures` 依然通过（依赖 Step 4 新增的"测试注入 BASIC_ONLY"入口）；
  - 确认没有新增任何 `http.Client{}` 字面量；
  - 确认 README 描述的环境变量顺序没被本 Step 意外破坏（README 更新留给 Step 8）。
- **提交信息建议**：`feat(satellite): wire LCM_BMC_AUTH_MODE & session TTL cap into Config`
- **Rollback**：`git revert HEAD`；回滚后 `NewCollector` 退回"永远 BASIC_ONLY"的行为，是安全默认。

### Step 5 — 升级 `vendor_fixture_test.go` 的 fixture harness，table-driven BASIC × SESSION

- **目标**：把 [newFixtureTLSServer](../satellite/pkg/redfish/vendor_fixture_test.go#L152) 升级成"同一份 handler 可按模式切换 BASIC_ONLY / SESSION_PREFERRED / SESSION_ONLY_UNSUPPORTED / SESSION_MISSING_TOKEN"，并把 `TestVendorTemplateFixtures` 扩成 table-driven × 两种认证模式。
- **修改文件**：
  - `satellite/pkg/redfish/vendor_fixture_test.go` — 重写 handler：支持 `POST /redfish/v1/SessionService/Sessions` 生成 token + 回 `X-Auth-Token` + `Location`；支持 DELETE；支持通过外部变量控制"是否支持 session"与"是否返回 token"。
  - 四份 vendor fixture JSON（[openbmc-baseline.json](../satellite/pkg/redfish/testdata/vendor-fixtures/openbmc-baseline.json) / [dell-idrac.json](../satellite/pkg/redfish/testdata/vendor-fixtures/dell-idrac.json) / [hpe-ilo.json](../satellite/pkg/redfish/testdata/vendor-fixtures/hpe-ilo.json) / [lenovo-xcc.json](../satellite/pkg/redfish/testdata/vendor-fixtures/lenovo-xcc.json)）补充 `SessionService` 元数据与 `Sessions` collection 的最小 stub。
- **关键约束**：
  - **不修改 Core 侧 `RedfishMockServer`**（Phase 7 契约）。
  - 保证原有 `TestVendorTemplateFixtures`（BASIC 模式）仍然独立可跑。
  - 新增的 `TestVendorFixturesBasicAndSessionModes` 不依赖真实 TLS 证书，继续复用 `httptest.Server` 自签。
- **新增测试**：
  - `TestVendorFixturesBasicAndSessionModes` — table-driven：`{vendor, mode} ∈ {openbmc,dell,hpe,lenovo} × {BASIC_ONLY, SESSION_PREFERRED}` 共 8 个 subtest，断言型号 / 电源 / 温度。
- **测试命令**：
  ```
  cd satellite && go test ./pkg/redfish/... -run TestVendorFixtures -count=1 -race -v
  cd satellite && go test ./pkg/redfish/... -count=1 -race
  cd satellite && go test ./... -count=1
  ```
- **自审**：
  - 确认 Step 1~4 所有测试继续绿；
  - 确认新 handler 不会让旧 test `TestVendorTemplateFixtures` 行为改变（应仍以 BASIC_ONLY 路径跑通）；
  - 确认 fixture JSON 中新增的 `SessionService` 片段**不使用真实 BMC 地址或凭据**；
- **提交信息建议**：`test(satellite): extend vendor fixture harness to exercise session-auth path`
- **Rollback**：`git revert HEAD`；回滚不影响 Step 1~4 的任何生产代码行为。

### Step 6 — `main.go` 优雅退出接 `collector.Close()`

- **目标**：让进程退出时触发 best-effort session `DELETE`，兑现 Claude Review 的建议。
- **修改文件**：
  - `satellite/cmd/satellite/main.go` — 在现有 `sigChan` 处理路径上 `defer collector.Close(ctx)` 或在退出流程里显式调用；`ctx` 用 2 秒超时的 `context.WithTimeout`。
  - `satellite/pkg/redfish/client.go` — 如果 Step 1 的 `Transport.Close` 已经在 `Collector` 层暴露，这里确认签名稳定；否则追加一层薄封装。
- **不触碰**：`pkg/redfish/` 之外的任何非 `main.go` 文件；尤其不得因为这一步去"顺手修"其它 shutdown 路径（gRPC / Kafka / PXE）。
- **新增测试**：
  - 本 Step 不新增单元测试（`main.go` 的信号路径在 Go 单元测试里不方便直接覆盖）。`TestSessionCloseDeletesKnownSessionsBestEffort` 已在 Step 1 覆盖了 collector 级别的语义。
  - 改用人工 smoke：`LCM_BMC_IP=<mock> go run ./cmd/satellite` 后 `Ctrl-C`，观察日志里出现一条 `session DELETE best-effort` 记录。**人工 smoke 结果写进提交信息正文**（记录命令与观察到的日志行）。
- **测试命令**：
  ```
  cd satellite && go build ./...
  cd satellite && go test ./... -count=1
  cd satellite && go vet ./...
  ```
- **自审**：
  - 四问全答；
  - 确认 `main.go` 信号处理顺序：先关业务，再关 tracer；`collector.Close()` 在 tracer 关之前（tracer 一关就没链路追踪了）。
- **提交信息建议**：`feat(satellite): trigger best-effort Redfish session cleanup on graceful shutdown`
- **Rollback**：`git revert HEAD`；session 清理回退到"进程退出后靠 BMC TTL 自行过期"，这是可接受的兜底。

### Step 7 — 硬件准入矩阵骨架 + `pending:` 段

- **目标**：把三份 per-machine markdown 骨架与 `matrix.yaml` 的 `pending:` 段落盘，让后续实验台一就位即可填数据。
- **新增文件**：
  - `documentation/hardware-acceptance/dell-idrac-reference.md`
  - `documentation/hardware-acceptance/hpe-ilo-reference.md`
  - `documentation/hardware-acceptance/lenovo-xcc-reference.md`
- **修改文件**：
  - [documentation/hardware-acceptance/matrix.yaml](hardware-acceptance/matrix.yaml) — 在 schema 下方追加 `pending:` 段：
    ```
    pending:
      - vendor: Dell
        model: iDRAC (TBD)
        ref: dell-idrac-reference.md
        owner: TBD
      - vendor: HPE
        model: iLO (TBD)
        ref: hpe-ilo-reference.md
        owner: TBD
      - vendor: Lenovo
        model: XCC (TBD)
        ref: lenovo-xcc-reference.md
        owner: TBD
    ```
- **不触碰**：`machines: []`（留给真实验收 PR）；[openbmc-reference.md](hardware-acceptance/openbmc-reference.md)；Core 侧 `RedfishMockServer`。
- **测试命令**：
  ```
  python3 -c "import yaml,sys; y=yaml.safe_load(open('documentation/hardware-acceptance/matrix.yaml')); assert len(y.get('pending',[]))==3; print('pending OK')"
  ```
  （若本地无 PyYAML，使用 `grep -c '^  - vendor:' documentation/hardware-acceptance/matrix.yaml` 应返回 3。）
- **自审**：
  - 确认三份 markdown 骨架章节顺序与 [openbmc-reference.md](hardware-acceptance/openbmc-reference.md) 完全一致；
  - 确认 `Mock fixture link` 字段分别指向对应 vendor 的 `satellite/pkg/redfish/testdata/vendor-fixtures/<vendor>.json`；
  - 确认骨架里所有实测字段都是 `TBD`，没有随手编造的参数。
- **提交信息建议**：`docs(hardware-acceptance): seed Dell/HPE/Lenovo skeletons and pending tracker`
- **Rollback**：`git revert HEAD`；纯文档，无风险。

### Step 8 — README / Helm / PROJECT_STATUS 收尾同步

- **目标**：把新环境变量的对外文档、Helm chart 占位与 PROJECT_STATUS 的状态行一起收尾。
- **修改文件**：
  - [satellite/README.md](../satellite/README.md) — 在 `LCM_REDFISH_TEMPLATE_NAME` 附近追加两行：`LCM_BMC_AUTH_MODE` / `LCM_BMC_SESSION_TTL_SECONDS_MAX`，并用一段短说明解释三种模式。
  - [helm/hyperscale-lcm/values.yaml](../helm/hyperscale-lcm/values.yaml) — satellite 段 `env:` 下追加两个 key 占位，默认注释掉（与现有 `# KEY: value` 风格保持一致）。
  - [documentation/PROJECT_STATUS.md](PROJECT_STATUS.md) — **第二次**更新（第一次已在 Step 0 落盘）：把 1.2 节的"Redfish / BMC Phase 7 深化"行里的"Phase 8 已规划"改为"Phase 8 实现中"（或等合入再改为"已落地"，视合入时机决定）。
- **不触碰**：`REDFISH_BMC_PHASE7_PLAN.md`、`REDFISH_BMC_PHASE8_PLAN.md` 正文与 Codex/Claude 附录。
- **测试命令**：
  ```
  cd satellite && go test ./... -count=1
  ./scripts/check_ci_contract.sh
  ```
- **自审**：
  - README 里新增的环境变量默认值与 `config.go` 里的默认值**完全一致**（两处字面量漂移是典型 regression 源）；
  - Helm values.yaml 的注释风格与同文件上下其他条目保持一致；
  - PROJECT_STATUS 顶部 `Last Updated` 刷新到本次合入日。
- **提交信息建议**：`docs(phase8): document session auth env vars and refresh PROJECT_STATUS`
- **Rollback**：`git revert HEAD`。

### Step 9 — 全量回归 + 开 PR

- **目标**：所有 Step 合入分支后，跑一次完整回归，开 PR。
- **动作**：
  1. `./scripts/check_ci_contract.sh`
  2. `cd satellite && go test ./... -count=1 -race`
  3. `cd core && ./gradlew check --no-daemon`（Phase 7 回归冒烟，**即使未触碰 core 也必须跑**，防止 E2E 或 JaCoCo 出现远端 regression）
  4. `cd frontend && npm run build`（冒烟，确认 Phase 8 不意外影响前端构建）
  5. `git push -u origin feat/phase8-satellite-session`
  6. `gh pr create` —— PR 标题 `feat(satellite): Phase 8 session-aware Redfish + hardware matrix seed`，PR 描述需包含：
     - Why（Phase 7 延后项 + Option A 决策引用）
     - Step 1~8 的提交列表与一句话摘要
     - Test plan 勾选项（对齐本节"测试命令"）
     - Out of scope（AlertManager、Playwright、多集群联邦、EventService 等）
- **不做的事**：
  - 不合并提交为 squash；PR 里保留 9 个提交的历史，方便 code review 逐步对齐。
  - 不在 PR 里附带任何非 Phase 8 主题的改动。

### 跨 Step 的风险与兜底

| 风险 | 触发步骤 | 兜底 |
|------|---------|------|
| Step 2 里 `go mod tidy` 顺手升级其它依赖 | Step 2 | 提交前 `git diff go.mod go.sum`，**仅允许移除 `gofish` 相关行**；其它变更回滚后再 `go mod tidy` |
| Step 3 迁 template_adapter 时遗漏 `SetBasicAuth` 调用 | Step 3 | 提交前 grep 兜底（见 Step 3 的测试命令），CI 里额外加一条 grep guard 留到 Step 5 做 |
| Step 4 切换默认到 `SESSION_PREFERRED` 后 vendor_fixture_test 崩 | Step 4 | 在 Step 4 保留"测试用 BASIC_ONLY 注入入口"，Step 5 再把 test 扩成两模式 |
| Step 5 fixture handler 行为漂移，意外让旧 test 变绿但实际没跑新路径 | Step 5 | table-driven 每个 subtest 显式断言 mode；Test 代码用 `t.Logf` 打一条"executed via X path"防止静默 |
| Step 6 smoke 只人工跑一次，合入后回归失效 | Step 6 | 在 PR 描述里记录人工 smoke 的命令与时间戳，作为后续回归的手动基线 |
| Step 7 `pending:` 段字段名与未来真实验收 PR 不兼容 | Step 7 | 字段命名直接对齐 `matrix.yaml` 顶部 schema；如字段缺失，在 schema 里补一条 `pending:` 的 key 说明 |

### 合入后的验证回执

- PR 合入 main 后，**由 Phase 8 负责人**在本文件 `Implementation Notes` 章节按 Phase 7 惯例回填三个子标题（`Satellite transport/session`、`Fixture & regression`、`Hardware matrix pending tracker`）。这是 Phase 8 作为"已落地"状态的最终认定标志。
- 回填动作本身作为一个独立的 `docs(phase8): record implementation notes` 提交，不带任何代码修改。
