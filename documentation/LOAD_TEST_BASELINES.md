# Load Test Baselines

> **Last Updated:** 2026-04-18 (expanded to the most recent 5 green `push -> main` runs)
> **Scope:** 本文件是 `CI/CD Pipeline` 中 `Core Load Test` 的单一趋势基线入口。只记录最近 5 次绿色运行的标准化摘要；CI 日志与 artifact 是原始证据，但不是长期趋势事实源。

---

## 1. 固定口径

### 当前 CI 负载轮廓

| 项 | 当前值 |
|----|--------|
| Workflow | `CI/CD Pipeline` |
| Job | `Core Load Test` |
| Loadgen 命令 | `./loadgen -addr localhost:8080 -plaintext -conns 10 -sats 20 -interval 1s -duration 30s` |
| 总卫星数 | `10 x 20 = 200` |
| 运行时长 | `30s` |
| Readiness probe | `curl -sf http://localhost:8080/health/ready` |
| Post-load liveness probe | `curl -sf http://localhost:8080/health/live` |

### 当前阈值

| 指标 | 阈值 |
|------|------|
| registration success rate | `>= 95%` |
| heartbeat success rate | `>= 99%` |
| heartbeat failures | `<= max(5, heartbeatAttempts * 1%)` |

阈值与 workload 规模的事实源始终以 [CI_CONTRACT.md](CI_CONTRACT.md) 和 [.github/workflows/ci.yml](../.github/workflows/ci.yml) 为准；本文件只负责记录绿色运行结果。

---

## 2. 最近绿色基线

| 时间 (UTC) | Run ID | Commit SHA | Job | 规模 | 时长 | Registration | Heartbeat | Heartbeat Failures | 备注 |
|------------|--------|------------|-----|------|------|--------------|-----------|--------------------|------|
| 2026-04-18 10:24 | `24602586038` | `73ac293a805c12b6769c07f250ab91abea6febcc` | `71943727340` (`Core Load Test`) | `10 x 20 = 200` | `30s` | `200/200 (100%)` | `5364/5364 (100%)` | `0` | 最新绿色 push run；Software Closure Round 2 文档同步后再次验证通过 |
| 2026-04-18 09:36 | `24601816322` | `b50f78c2c39498f3828745d8fc25dd90c8f28626` | `71941779435` (`Core Load Test`) | `10 x 20 = 200` | `30s` | `200/200 (100%)` | `5540/5540 (100%)` | `0` | 绿色 push run；文档刷新后基线稳定 |
| 2026-04-17 02:13 | `24544092198` | `58f1ba7f3d9abcc91abf46fb7f784ae1a9d8f25a` | `71756066679` (`Core Load Test`) | `10 x 20 = 200` | `30s` | `200/200 (100%)` | `5350/5355 (99.907%)` | `5` | 达到当前阈值上限 `max(5, 1%)` |
| 2026-04-15 12:45 | `24455026576` | `4325229ddcdc7f34960b28a873780a2e9600e467` | `71453757049` (`Core Load Test`) | `10 x 20 = 200` | `30s` | `200/200 (100%)` | `5325/5325 (100%)` | `0` | Playwright E2E 上线后的绿色 push run |
| 2026-04-15 06:19 | `24439463794` | `e450fd13e9d359c4d39c4046471be4038744981d` | `71401132444` (`Core Load Test`) | `10 x 20 = 200` | `30s` | `200/200 (100%)` | `5480/5481 (99.982%)` | `1` | AlertManager 合并后的绿色 push run |

---

## 3. 原始证据索引

- `2026-04-18 10:24 UTC` — workflow run: `https://github.com/wenbingkun/hyperscale-lcm/actions/runs/24602586038`；job: `https://github.com/wenbingkun/hyperscale-lcm/actions/runs/24602586038/job/71943727340`；`LOADGEN_SUMMARY {"durationSeconds":30,"connections":10,"satellitesPerConnection":20,"totalSatellites":200,"activeSatellites":0,"registrationSuccessRate":1,"heartbeatSuccessRate":1,"registrationAttempts":200,"registrationSuccess":200,"registrationFailures":0,"heartbeatAttempts":5364,"heartbeatSuccess":5364,"heartbeatFailures":0}`
- `2026-04-18 09:36 UTC` — workflow run: `https://github.com/wenbingkun/hyperscale-lcm/actions/runs/24601816322`；job: `https://github.com/wenbingkun/hyperscale-lcm/actions/runs/24601816322/job/71941779435`；`LOADGEN_SUMMARY {"durationSeconds":30,"connections":10,"satellitesPerConnection":20,"totalSatellites":200,"activeSatellites":0,"registrationSuccessRate":1,"heartbeatSuccessRate":1,"registrationAttempts":200,"registrationSuccess":200,"registrationFailures":0,"heartbeatAttempts":5540,"heartbeatSuccess":5540,"heartbeatFailures":0}`
- `2026-04-17 02:13 UTC` — workflow run: `https://github.com/wenbingkun/hyperscale-lcm/actions/runs/24544092198`；job: `https://github.com/wenbingkun/hyperscale-lcm/actions/runs/24544092198/job/71756066679`；`LOADGEN_SUMMARY {"durationSeconds":30,"connections":10,"satellitesPerConnection":20,"totalSatellites":200,"activeSatellites":0,"registrationSuccessRate":1,"heartbeatSuccessRate":0.9990662931839402,"registrationAttempts":200,"registrationSuccess":200,"registrationFailures":0,"heartbeatAttempts":5355,"heartbeatSuccess":5350,"heartbeatFailures":5}`
- `2026-04-15 12:45 UTC` — workflow run: `https://github.com/wenbingkun/hyperscale-lcm/actions/runs/24455026576`；job: `https://github.com/wenbingkun/hyperscale-lcm/actions/runs/24455026576/job/71453757049`；`LOADGEN_SUMMARY {"durationSeconds":30,"connections":10,"satellitesPerConnection":20,"totalSatellites":200,"activeSatellites":0,"registrationSuccessRate":1,"heartbeatSuccessRate":1,"registrationAttempts":200,"registrationSuccess":200,"registrationFailures":0,"heartbeatAttempts":5325,"heartbeatSuccess":5325,"heartbeatFailures":0}`
- `2026-04-15 06:19 UTC` — workflow run: `https://github.com/wenbingkun/hyperscale-lcm/actions/runs/24439463794`；job: `https://github.com/wenbingkun/hyperscale-lcm/actions/runs/24439463794/job/71401132444`；`LOADGEN_SUMMARY {"durationSeconds":30,"connections":10,"satellitesPerConnection":20,"totalSatellites":200,"activeSatellites":0,"registrationSuccessRate":1,"heartbeatSuccessRate":0.9998175515416895,"registrationAttempts":200,"registrationSuccess":200,"registrationFailures":0,"heartbeatAttempts":5481,"heartbeatSuccess":5480,"heartbeatFailures":1}`

---

## 4. 追加规则

1. 仅在 `Core Load Test` 为绿色且日志中存在 `LOADGEN_SUMMARY` 时追加新记录。
2. 新记录按时间倒序追加在表格顶部，只保留最近 5 次绿色运行。
3. 每条记录至少包含：`run ID`、`commit SHA`、`satellites`、`duration`、`registration success rate`、`heartbeat success rate`、`heartbeat failures`。
4. 如果 workload 规模、阈值、probe 路径或 transport 模式发生变化，必须先按 [CI_CONTRACT.md](CI_CONTRACT.md) 走变更说明，再在本文件中显式记录旧值、新值与调整理由。
5. 不要把单次失败 run 写入本文件；失败 run 只应留在 GitHub Actions 日志与排障记录中。
