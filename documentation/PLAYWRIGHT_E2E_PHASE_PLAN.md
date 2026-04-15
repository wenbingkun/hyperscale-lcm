# Phase Playwright E2E：浏览器级回归体系建立（P1 收口）

> Updated: 2026-04-15
> 参照 [ALERTMANAGER_PHASE_PLAN.md](ALERTMANAGER_PHASE_PLAN.md) 格式编写。

## Summary

- Phase Playwright E2E 专注于为 Hyperscale LCM 前端建立**浏览器级端到端回归测试体系**，是 [PROJECT_STATUS.md](PROJECT_STATUS.md) §1.1「集成测试」与 §1.3「Playwright 浏览器级回归」两个 🟡 项的收口工作。
- 当前状态梳理（2026-04-15）：
  1. `@playwright/test` v1.57.0 已在 `frontend/package.json` 的 `devDependencies` 中声明，`test:e2e` / `test:e2e:ui` 脚本已注册。
  2. `frontend/playwright.config.ts` 已存在，配置了 Chromium 项目、`webServer` 指向 `npm run dev` (port 5173)、CI 模式下 `retries: 2` + `workers: 1`。
  3. **但** `frontend/e2e/` 目录不存在——**没有任何实际 E2E 测试用例**。Playwright 是"装了但没用"。
  4. 现有 13 个 Vitest 组件测试（29 个 test case）全部基于 `jsdom` + `@testing-library/react`，只验证组件行为，**不覆盖浏览器渲染、路由导航、页面间跳转、真实 CSS 布局**。
  5. 前端有 9 个页面路由：`/login`、`/`（Dashboard）、`/satellites`、`/satellites/:id`、`/topology`、`/jobs`、`/jobs/:id`、`/discovery`、`/credential-profiles`、`/tenants`。所有受保护路由需 JWT 认证。
  6. API 层 (`src/api/client.ts`) 统一走 `apiFetch()` 封装，`API_BASE` 默认 `http://localhost:8080`。可通过 Playwright `page.route()` 整体拦截。
  7. CI 中没有 E2E 相关 job；`.gitignore` 没有排除 Playwright 产物（`test-results/`、`playwright-report/`）。
- Phase 定位：**从零建立浏览器级 E2E 回归体系**，不是对现有 Vitest 测试的替代。
- 明确**不做**：不改动任何 React 组件代码（除非需要补 `data-testid` 属性）；不改 API 后端代码；不改 Vitest 测试；不做性能测试 / 视觉回归测试（留作 follow-up）；不引入 Cypress / Selenium 等替代框架。

## Key Changes

以下按执行顺序组织。每步完成后必须在本地跑对应验证 → 自审 4 问 → commit；禁用 `git commit --no-verify` 与 `git push --force`。

### Step 0 — 基线确认（纯文档）

**目标**：本份 `PLAYWRIGHT_E2E_PHASE_PLAN.md` 固化现状 + Phase 边界。

**自审 4 问**：
- 文档是否把 Phase 边界写死（避免 scope creep）？
- 是否列出所有现有文件的锚点以便后续 PR 审查参照？
- 是否标记好 "follow-up"（视觉回归、多浏览器矩阵、移动端视口）？
- commit 后 CI 是否仍然绿（纯文档改动应无风险）？

**commit**：`docs: add Phase Playwright E2E browser regression plan`

### Step 1 — 基础设施搭建：fixtures + helpers + .gitignore

**目标**：建立测试基础设施，让后续 Step 专注于写测试逻辑。

**新增**：`frontend/e2e/fixtures/` 目录

#### 1.1 API Mock 层（`e2e/fixtures/api-mocks.ts`）

定义统一的 API route 拦截 helper，让每个测试文件不需要重复写 `page.route()`。核心思路：

```typescript
import { Page } from '@playwright/test';

// 预设的 mock 数据
export const MOCK_JWT = 'eyJ...'; // 有效 JWT (exp 在未来)
export const MOCK_USER = { username: 'admin', roles: ['ADMIN'], tenantId: 'default' };

export const MOCK_CLUSTER_STATS = {
  onlineNodes: 42, totalNodes: 50, totalCpuCores: 1600,
  totalGpus: 200, totalMemoryGb: 12800,
};

export const MOCK_JOB_STATS = {
  pending: 3, scheduled: 2, running: 5, completed: 120, failed: 2,
};

export const MOCK_SATELLITES = [/* 2-3 个示例 satellite */];
export const MOCK_JOBS = [/* 3-5 个不同状态的 job */];
export const MOCK_DISCOVERED_DEVICES = [/* 2-3 个发现设备 */];
export const MOCK_CREDENTIAL_PROFILES = [/* 1-2 个凭据配置 */];

/**
 * 拦截所有 /api/* 请求并返回 mock 数据。
 * 可选 overrides 覆盖特定端点。
 */
export async function mockAllApiRoutes(page: Page, overrides?: Record<string, unknown>): Promise<void>;

/**
 * 模拟登录流程：拦截 /api/auth/login 返回 JWT，
 * 并在 localStorage 预设 token + user。
 */
export async function loginAsAdmin(page: Page): Promise<void>;
```

**设计要点**：
- Mock 数据要尽量贴近真实 API 的 schema（来自 `src/api/client.ts` 的 TypeScript 接口）
- `loginAsAdmin()` 直接操作 `localStorage` 注入 token（避免每个测试都走 UI 登录流程），但登录页测试除外
- 对 WebSocket `/ws/dashboard` 走 `page.route('**/ws/**', route => route.abort())` 防止连接失败导致测试噪音

#### 1.2 扩展 test fixture（`e2e/fixtures/test-fixtures.ts`）

```typescript
import { test as base } from '@playwright/test';

// 自定义 fixture 扩展：自动登录 + mock API
export const test = base.extend<{ authenticatedPage: Page }>({
  authenticatedPage: async ({ page }, use) => {
    await mockAllApiRoutes(page);
    await loginAsAdmin(page);
    await use(page);
  },
});

export { expect } from '@playwright/test';
```

#### 1.3 `.gitignore` 追加

```
# Playwright
frontend/test-results/
frontend/playwright-report/
frontend/blob-report/
frontend/playwright/.cache/
```

**修改**：[playwright.config.ts](../frontend/playwright.config.ts)
- 追加 `outputDir: 'test-results'` 显式指定产物路径
- 追加 `expect: { timeout: 5_000 }` 全局断言超时
- `webServer.env` 中设置 `VITE_API_BASE` 防止 dev server 尝试连真实后端

**自审 4 问**：
- `loginAsAdmin()` 注入的 JWT 是否能通过前端 `parseJwt()` 的校验（含 exp 检查）？
- `mockAllApiRoutes()` 是否覆盖了 DashboardPage 挂载时调用的所有 API（`/api/nodes/stats`、`/api/jobs/stats`、`/api/nodes`）？
- `.gitignore` 是否正确排除了 CI 和本地产物？
- Playwright 浏览器二进制是否需要 `npx playwright install chromium`？（是；CI 需要单独一步）

**commit**：`feat(frontend): scaffold playwright e2e test fixtures and api mock layer`

### Step 2 — 登录页 E2E 测试

**新文件**：`frontend/e2e/login.spec.ts`

覆盖场景：
1. **未登录访问根路径 → 重定向到 /login**：访问 `/`，断言被重定向到 `/login`
2. **登录表单渲染**：断言 username / password / tenant 三个输入框 + submit 按钮存在（通过 `id` 选择器：`#login-username`、`#login-password`、`#login-tenant`、`#login-submit`）
3. **空字段提交 → 显示错误**：不填信息直接点提交，断言出现错误提示
4. **成功登录 → 跳转到 Dashboard**：mock `/api/auth/login` 返回 JWT，填写表单并提交，断言 URL 变为 `/`，断言 Dashboard 渲染了 stat card
5. **登录失败 → 显示错误**：mock 返回 401，断言错误消息出现

**自审 4 问**：
- 登录表单的 input 是否都有唯一 `id`？（已有：`#login-username`、`#login-password`、`#login-tenant`、`#login-submit`）
- 测试是否避免了 hardcode 等待时间（用 `toBeVisible` / `toHaveURL` 的自动重试替代 `page.waitForTimeout`）？
- 重定向断言是否考虑了 React Router 的 `replace` 语义？
- 是否在 CI 无头模式下同样能通过？

**commit**：`test(frontend): add login page e2e tests`

### Step 3 — Dashboard 页 E2E 测试

**新文件**：`frontend/e2e/dashboard.spec.ts`

使用 `authenticatedPage` fixture（自动注入 token + mock API），覆盖：
1. **Dashboard 渲染**：导航到 `/`，断言 4 个 stat card 可见（Total Nodes、GPU Capacity、Active Jobs、Network）
2. **Stat card 显示 mock 数据**：断言 Total Nodes 值为 `"42"`（来自 `MOCK_CLUSTER_STATS.onlineNodes`）
3. **Satellite 表格渲染**：断言 SatelliteTable 组件出现，且至少包含 mock 数据中的 hostname
4. **Job 提交表单存在**：断言右侧 JobSubmissionForm 区域可见
5. **侧边栏导航**：点击侧边栏 "Satellites" 链接，断言 URL 变为 `/satellites`

**自审 4 问**：
- Dashboard 挂载时调用的 `fetchClusterStats()` / `fetchJobStats()` 是否都被 mock 覆盖？
- AreaChart（Recharts）在无头 Chromium 中是否能渲染（SVG 应该没问题）？
- `SatelliteTable` 组件单独调用 `fetchSatellites()`，是否也被 mock？
- framer-motion 动画是否影响元素可见性判断？（Playwright 默认等到元素 visible，应该不影响）

**commit**：`test(frontend): add dashboard page e2e tests`

### Step 4 — 设备发现页 E2E 测试

**新文件**：`frontend/e2e/discovery.spec.ts`

设备发现页是功能最复杂的页面（25KB 代码），覆盖：
1. **页面加载**：导航到 `/discovery`，断言发现设备表格渲染
2. **设备列表展示**：断言 mock 数据中的设备 IP / MAC 地址出现
3. **筛选功能**：使用搜索框过滤设备，断言结果集正确
4. **Approve 操作**：mock `POST /api/discovery/{id}/approve` 返回成功，点击 approve 按钮，断言状态变化
5. **Claim 规划操作**：mock `POST /api/discovery/{id}/claim-plan` 返回成功

**自审 4 问**：
- DiscoveryPage 的 `fetchDiscoveredDevices()` 和 `fetchPendingDiscoveryCount()` 是否被 mock？
- Approve / Reject 按钮是否有唯一选择器？（可能需要用 `getByRole` + 行定位）
- BMC 相关面板（capabilities、power actions）需要额外 mock 端点吗？（Step 4 暂不覆盖 BMC 深层操作，留作 follow-up）
- 是否需要给 DiscoveryPage 补充 `data-testid`？（先尝试语义选择器，不够再补）

**commit**：`test(frontend): add discovery page e2e tests`

### Step 5 — 作业管理页 E2E 测试

**新文件**：`frontend/e2e/jobs.spec.ts`

覆盖：
1. **作业列表页**：导航到 `/jobs`，断言 mock 数据中的 job name 出现
2. **状态 badge 渲染**：断言不同状态的 job 有对应的颜色 badge
3. **提交新作业**：在 Dashboard 的 JobSubmissionForm 中填写表单，mock `POST /api/jobs` 返回成功，断言提交成功反馈
4. **作业详情页**：点击某个 job 行，导航到 `/jobs/:id`，断言详情信息渲染

**自审 4 问**：
- JobsPage 调用 `fetchJobs()`，JobDetailPage 可能调用单独的 job 详情 API —— 是否需要额外 mock？
- 作业提交后前端是否自动 refetch 列表？（是否需要 mock 第二次 `fetchJobs` 返回含新 job 的列表？）
- `executionType` 选择（Docker / SSH / Shell）是否需要覆盖？
- 作业取消 / 重试是否在 scope 内？（不在，留作 follow-up）

**commit**：`test(frontend): add job management e2e tests`

### Step 6 — 拓扑与卫星页 E2E 测试

**新文件**：`frontend/e2e/topology-satellites.spec.ts`

覆盖：
1. **Satellites 列表页**：导航到 `/satellites`，断言 satellite 卡片渲染
2. **Satellite 详情页**：点击 satellite → 导航到 `/satellites/:id`，断言硬件信息渲染（CPU、GPU、Memory）
3. **Topology 页**：导航到 `/topology`，断言拓扑可视化区域渲染（SVG / canvas 元素存在）
4. **Zone / Rack 层级导航**：如果拓扑有交互分层，断言点击 zone 展开 rack 列表

**自审 4 问**：
- TopologyPage（35KB）内部有复杂的 SVG 渲染——在无头浏览器中是否稳定？
- SatelliteDetailPage 是否需要 mock `fetchSatellites()` 中的单个 satellite 数据？
- 拓扑数据来自哪个 API 端点？（需要补充 mock）
- 是否需要为 TopologyPage 的交互元素补充 `data-testid`？

**commit**：`test(frontend): add topology and satellite detail e2e tests`

### Step 7 — 凭据配置页 E2E 测试

**新文件**：`frontend/e2e/credential-profiles.spec.ts`

覆盖：
1. **列表渲染**：导航到 `/credential-profiles`，断言 mock profile 出现
2. **创建凭据**：点击创建按钮，填写表单，mock `POST /api/credential-profiles` 返回成功，断言成功提示
3. **验证凭据**：mock `POST /api/credential-profiles/{id}/validate` 返回验证结果，点击验证按钮

**自审 4 问**：
- 创建表单中有 Redfish template 下拉，是否需要 mock `/api/credential-profiles/templates` 端点？（需要）
- 编辑 / 删除流程是否在 scope 内？（创建 + 验证足够，编辑删除留 follow-up）
- 表单有多个 optional 字段——是否只填必填项？
- CMDB Sync 等高级功能是否覆盖？（不覆盖）

**commit**：`test(frontend): add credential profiles page e2e tests`

### Step 8 — CI 集成

**修改**：[.github/workflows/ci.yml](../.github/workflows/ci.yml)

在现有 `frontend-build` job 后新增 `frontend-e2e` job：

```yaml
frontend-e2e:
  name: Frontend E2E Tests
  runs-on: ubuntu-latest
  needs: [ci-contract-guard]

  steps:
    - uses: actions/checkout@v4

    - name: Set up Node.js ${{ env.NODE_VERSION }}
      uses: actions/setup-node@v4
      with:
        node-version: ${{ env.NODE_VERSION }}
        cache: 'npm'
        cache-dependency-path: frontend/package-lock.json

    - name: Install dependencies
      working-directory: frontend
      run: npm ci

    - name: Install Playwright browsers
      working-directory: frontend
      run: npx playwright install --with-deps chromium

    - name: Run E2E tests
      working-directory: frontend
      run: npx playwright test

    - name: Upload Playwright report
      if: failure()
      uses: actions/upload-artifact@v4
      with:
        name: playwright-report
        path: frontend/playwright-report/
        retention-days: 7
```

**关键点**：
- E2E 测试全部使用 API mock（无需 PostgreSQL / Redis / Kafka），所以不需要 services 块
- `npx playwright install --with-deps chromium` 安装浏览器 + 系统依赖（CI ubuntu 需要）
- 失败时上传 HTML report 方便调试
- Job 并行于 `frontend-build`，不阻塞其他 job

**自审 4 问**：
- 是否与 [CI_CONTRACT.md](CI_CONTRACT.md) 的高风险改动矩阵冲突？（`.github/workflows/**` 属于高风险，需跑验证矩阵）
- job 运行时间预估？（npm ci ~20s + playwright install ~30s + 7 spec files ~30s = ~80s）
- `npx playwright install --with-deps` 在 ubuntu-latest 上是否兼容？（官方支持）
- webServer 启动超时 120s 是否足够？（Vite dev server 通常 \<5s）

**commit**：`ci: add frontend E2E playwright job`

### Step 9 — 补充 data-testid 属性（按需）

**目标**：如果 Step 2-7 实现过程中发现某些元素无法通过语义选择器（`getByRole` / `getByText`）稳定定位，在此 Step 中集中补充 `data-testid` 属性。

**原则**：
- 优先使用 Playwright 推荐的语义定位器（`getByRole`、`getByLabel`、`getByText`、`getByPlaceholder`）
- 只在以下情况使用 `data-testid`：动态列表行（需要 per-row 唯一标识）、无文本的图标按钮、canvas/SVG 容器
- `data-testid` 命名规范：`{page}-{element}-{qualifier}`，如 `discovery-approve-btn-{deviceId}`

**自审 4 问**：
- 新增的 `data-testid` 是否足够语义化？
- 是否影响现有 Vitest 测试？（不影响，Vitest 用 `@testing-library/react` 不依赖 `data-testid`）
- 是否有 ESLint 规则限制 `data-testid`？（无）
- commit 是否只触碰组件 `*.tsx` 文件？

**commit**：`refactor(frontend): add data-testid attributes for e2e selector stability`

### Step 10 — PROJECT_STATUS.md 状态刷新

**修改**：[documentation/PROJECT_STATUS.md](PROJECT_STATUS.md)
- 顶部 `Last Updated` 刷到当日
- §1.1 「集成测试」行追加："Playwright E2E 覆盖 7 个主流程"
- §1.3 「Playwright 浏览器级回归」从 🟡 改为 ✅
- §3 P1 Playwright 行移除或标记已完成

**commit**：`docs: mark Phase Playwright E2E browser regression landed`

### Step 11 — 完整回归 + PR 提交

在推送 PR 前本地依次跑：
1. `./scripts/check_ci_contract.sh`
2. `cd frontend && npx playwright test`（全部 E2E 通过）
3. `cd frontend && npm test`（现有 29 个 Vitest 通过，不受影响）
4. `cd frontend && npm run lint && npm run build`
5. `cd satellite && go test ./... -count=1`（未改 Satellite 代码，回归保底）
6. 开 PR，等 CI 全绿（特别是新的 `frontend-e2e` job）
7. Review → merge（保留提交历史，不 squash）

**commit** 的时机：每个 Step 完成且自审通过后**立即 commit 并推送到 feature 分支**，不攒堆。

## Public Interfaces

- **Playwright 配置**：`frontend/playwright.config.ts`（已存在，微调）
- **E2E 测试目录**：`frontend/e2e/*.spec.ts`（全部新增）
- **Fixtures**：`frontend/e2e/fixtures/api-mocks.ts`、`frontend/e2e/fixtures/test-fixtures.ts`
- **npm scripts**（已存在，不变）：`test:e2e`（`playwright test`）、`test:e2e:ui`（`playwright test --ui`）
- **CI Job**：`frontend-e2e`（新增，并行于 `frontend-build`）
- **显式不变**：所有 Vitest 测试、所有 React 组件逻辑（仅可能补 `data-testid`）、Core / Satellite / Helm chart / Docker compose

## Test Plan

| 层 | 命令 | 新增 / 存量 |
|---|---|---|
| E2E 浏览器测试 | `cd frontend && npx playwright test` | 新增 ~7 spec files |
| Vitest 组件测试 | `cd frontend && npm test` | 存量回归（29 tests） |
| Frontend lint + build | `cd frontend && npm run lint && npm run build` | 存量 |
| CI E2E job | GitHub Actions `frontend-e2e` | 新增 CI job |
| Satellite | `cd satellite && go test ./... -count=1` | 存量回归 |
| CI Contract guard | `./scripts/check_ci_contract.sh` | 存量 |

**真实后端 E2E 不在 Phase 交付范围** — E2E 测试全部使用 API route mock。真实后端集成测试由 `scripts/demo.sh` 承载，是 follow-up 的 scope。

## Implementation Notes（占位，留给执行者回填）

- **`loginAsAdmin()` 的 JWT 构造**：需要构造一个 `parseJwt()` 能正确解析且 `exp` 在未来的 JWT。参照 `AuthContext.test.tsx` 中 `createJwt()` 的做法
- **WebSocket mock 策略**：`WebSocketProvider` 在 Dashboard 挂载时会尝试连接 `ws://localhost:8080/ws/dashboard`。需要拦截或关闭该连接以避免控制台错误
- **framer-motion 动画等待**：Playwright 默认 `actionability check` 会等待元素可见 + 稳定，但 framer-motion 的入场动画可能导致元素短暂 invisible。可能需要 `page.waitForSelector({ state: 'visible' })` 或调整 `animation: 'none'` 的 test 策略
- **Recharts SVG 断言**：Dashboard 的 AreaChart 渲染为 SVG，建议只断言 `<svg>` 容器存在，不断言 path 精确值
- **TopologyPage 的数据来源**：需确认 TopologyPage 调用哪些 API 端点来获取 zone/rack/fabric 数据，并补充相应 mock

## Assumptions

- Playwright v1.57+ 在 CI ubuntu-latest 上 `--with-deps` 安装即可运行 Chromium（不需额外 Docker 镜像）
- Vite dev server 在 CI 上启动时间 \<10s（远小于 `webServer.timeout: 120s`）
- API route mock 足以覆盖所有 E2E 测试需要的后端交互（不需要真实 Core 服务）
- 现有 React 组件的 `id` / 语义标签（`<button>` / `<input>` / `<h1>`）足够做大部分定位，`data-testid` 只是补充手段
- 前端没有 SSR / dynamic import 导致的路由 race condition（现在是 CSR + lazy import + Suspense）
- framer-motion 动画不会导致 Playwright 的 auto-wait 机制超时（如果出现，可通过 `prefers-reduced-motion` media query 在测试中禁用动画）

## 关键文件清单（Phase 执行阶段要触碰的文件）

**新增**：
- [documentation/PLAYWRIGHT_E2E_PHASE_PLAN.md](PLAYWRIGHT_E2E_PHASE_PLAN.md)（本文件，Step 0 产出）
- `frontend/e2e/fixtures/api-mocks.ts`（Step 1）
- `frontend/e2e/fixtures/test-fixtures.ts`（Step 1）
- `frontend/e2e/login.spec.ts`（Step 2）
- `frontend/e2e/dashboard.spec.ts`（Step 3）
- `frontend/e2e/discovery.spec.ts`（Step 4）
- `frontend/e2e/jobs.spec.ts`（Step 5）
- `frontend/e2e/topology-satellites.spec.ts`（Step 6）
- `frontend/e2e/credential-profiles.spec.ts`（Step 7）

**修改**：
- [frontend/playwright.config.ts](../frontend/playwright.config.ts) — 追加 outputDir / expect timeout / env（Step 1）
- [.gitignore](../.gitignore) — 追加 Playwright 产物排除（Step 1）
- [.github/workflows/ci.yml](../.github/workflows/ci.yml) — 新增 `frontend-e2e` job（Step 8）
- [documentation/PROJECT_STATUS.md](PROJECT_STATUS.md) — 状态刷新（Step 10）
- `frontend/src/pages/*.tsx` — 按需补 `data-testid`（Step 9，范围最小化）

**不得触碰**：
- Core 所有 Java 代码与配置
- Satellite 所有 Go 代码
- Helm chart 及 Docker compose
- 现有 Vitest 测试文件（`*.test.tsx` / `*.test.ts`）
- `lcm.proto` 与任何 gRPC 生成物
- `src/api/client.ts`（API 层不改动）

## 不做的事

- 不搭建真实 Core 后端作为 E2E 依赖（用 API route mock 替代）
- 不做视觉回归（pixel diff / screenshot comparison）——留作 follow-up
- 不做多浏览器矩阵（Firefox / WebKit）——当前只 Chromium，follow-up 可扩展
- 不做移动端视口测试——留作 follow-up
- 不做性能测试（page load time / Lighthouse）——留作 follow-up
- 不替换或修改现有 Vitest 组件测试
- 不改 Core / Satellite / Helm / Docker 代码
- 不引入 Cypress / Selenium / TestCafe 等替代框架
