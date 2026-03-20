# Redfish + 零接触纳管重构方案

## 1. 对话结论总结

### 1.1 Redfish 适配策略

- 不把某一家 BMC 当成唯一标准实现。
- 以 **标准 Redfish 对象模型** 为基线。
- 以 **OpenBMC** 作为第一套参考模板和基线适配器。
- 为不同厂商提供 **可导入的映射模板**，避免把接口差异写死在主业务代码中。
- Core 和上层业务只消费统一的内部模型，不直接耦合厂商路径。

### 1.2 零接触纳管边界

- 已启用认证的 BMC 不能合法地“跳过登录”。
- 真正可落地的“零接触纳管”应重新定义为：
  - 自动发现
  - 自动识别/分类
  - 自动匹配或引导首次凭据
  - 使用匹配到的凭据完成首次接管
  - 创建平台专用托管账号并进入持续管理

### 1.3 凭据接管策略

- 首次凭据必须来自外部可信来源，不能靠扫描猜测。
- 可用来源：
  - Vault / Secrets Manager
  - CMDB / 交付台账
  - 人工一次性录入
  - 带内 Agent / 主机侧引导
- 平台内部保存的是 **凭据档案描述和 secret ref**，而不是明文密码。
- 当前仓库内的落地方式是：
  - 外部 CMDB / 交付台账可直接通过在线 API 同步
  - 对于字段不对齐的企业接口，可通过 mapping profile 做路径映射与分页解析
  - 同步结果统一上收为高优先级 `CredentialProfile`
  - 继续复用同一套 claim 规划与 secret ref 解析链

## 2. 目标架构

### 2.1 Satellite 侧

- `Redfish Collector` 改为适配器式结构。
- `OpenBMCAdapter` 作为默认基线。
- `TemplateAdapter` 支持从本地 JSON 模板导入厂商路径映射。
- 保持现有 `Collector -> BuildRegisterRequest / BuildHeartbeatRequest` 调用面不变，降低影响面。

### 2.2 Core 侧

- 在 `DiscoveredDevice` 上补齐 claim 相关状态：
  - `authStatus`
  - `claimStatus`
  - `credentialProfile*`
  - `recommendedRedfishTemplate`
- 新增 `CredentialProfile`，描述：
  - 匹配规则
  - secret ref
  - 建议使用的 Redfish 模板
  - 外部来源审计字段（如 `sourceType / externalRef`）
  - 更细粒度的匹配键（如 `hostname / IP / MAC` pattern）
- 引入 `DeviceClaimPlanner`：
  - 识别 BMC 候选设备
  - 自动匹配凭据档案
  - 基于 `autoClaim` 和 secret ref 可用性输出 “可自动 claim” 或 “需要人工引导” 的状态
- 引入 `SecretRefResolver`：
  - 当前支持 `env://` 和 `literal://`（后者默认仅建议 dev/test）
  - 已接入最小可用的 `vault://` 解析能力，支持 HashiCorp Vault KV v1/v2
  - 只有 username/password 两个 secret ref 都可解析时，设备才进入 `READY_TO_CLAIM`
- `CredentialProfile` 提供单独的 secret ref 校验接口，允许运维在发现设备之前先验证档案是否可执行
- `core` 侧增加 Redfish 模板目录服务：
  - 内置常见厂商模板元数据
  - 可从外部 JSON 目录覆盖或追加
  - 能根据 `manufacturerHint / modelHint` 自动给发现设备推荐模板
- 引入 `RedfishClaimExecutor`：
  - 使用解析后的 secret ref 对真实 BMC 发起 Redfish 认证验证
  - 将成功/失败结果回写到 `AUTHENTICATED / CLAIMED` 或 `AUTH_FAILED`
  - 用真实响应补齐厂商/型号提示，并再次校正推荐模板
- 引入 `VaultSecretClient`：
  - 支持 `vault://<mount>/<path>#<field>`
  - 支持通过 query 覆盖 KV engine 版本，例如 `vault://secret/bmc/node?engine=1#username`
  - 支持 namespace、超时和短 TTL 缓存配置
- 引入 `BootstrapCredentialImportService`：
  - 接收 CMDB / 交付台账导出的 bootstrap 凭据记录
  - 按 `sourceType + externalRef` 或 `name` upsert 为 `CredentialProfile`
  - 默认提升优先级，使外部系统导入的精确匹配记录先于通用策略命中
- 引入 `CmdbBootstrapSyncService`：
  - 以标准化 JSON API 在线拉取外部 bootstrap 凭据记录
  - 支持手工触发与定时同步
  - 支持 mapping-file，将企业 API 的字段路径映射到内部 `CredentialProfileRequest`
  - 支持 `next page` 分页跟随，以及将 hostname / IP / MAC 自动转成精确匹配 pattern
  - 提供企业 CMDB 映射样例 `documentation/cmdb/enterprise-cmdb-bootstrap-profile.example.json`
  - 继续复用 `BootstrapCredentialImportService` 做 upsert，而不是再维护第二套同步落库逻辑
- 引入 `RedfishManagedAccountProvisioner`：
  - 在首次 bootstrap claim 成功后，按标准 Redfish `AccountService/Accounts` 创建或收敛平台托管账号
  - 托管账号的用户名、密码、角色同样走 secret ref，不在平台中保存明文

## 3. 当前阶段落地原则

- 不打断现有 `Satellite -> Register/Heartbeat -> Core` 主链路。
- 不改变现有 `DiscoveryStatus` 审批语义。
- 先完成：
  - 适配器化
  - 模板导入
  - claim 状态机与凭据档案
  - secret ref 解析与 readiness 判定
  - 模板目录与自动推荐
  - 首次 Redfish claim 验证执行
- 真实 Vault 集成以最小可用 KV 读取能力落地，后续再扩展到企业级 Secret Manager 适配层。
- 先落地标准 Redfish 路径上的托管账号创建与按需收敛；独立调度轮换保留到下一阶段。
- CMDB / 台账集成已支持在线同步；如果接入新的企业 CMDB 产品，优先通过 mapping profile 适配而不是改代码。

## 4. 影响评估

### 4.1 低风险兼容项

- `satellite/cmd/satellite/metrics.go` 仍通过原 `Collector` API 获取静态/动态 Redfish 数据。
- `core` 的注册、心跳、审批接口保持原路径不变。
- Discovery 现有 `PENDING/APPROVED/MANAGED` 流程不被移除。

### 4.2 新增但不强依赖的能力

- `CredentialProfile` REST API
- `CredentialProfile` bootstrap 导入 API
- `CredentialProfile` 在线 CMDB 同步 API
- 发现设备上的 claim 规划字段
- `LCM_REDFISH_TEMPLATE_DIR` / `LCM_REDFISH_TEMPLATE_NAME` 配置
- `lcm.claim.secret-resolver.*` 配置
- `lcm.claim.vault.*` 配置
- `lcm.redfish.template-catalog.dir` 配置
- `lcm.claim.redfish.*` 配置
- `lcm.cmdb.sync.*` 配置

### 4.3 后续仍需补齐

- BMC 平台专用账号权限收敛与密码轮换
- 托管账号独立调度轮换与失效回收
- 前端对 claim / auth 状态的进一步引导式操作
- 成功 claim 后将模板、认证状态与后续托管动作串成闭环
- 基于真实 Vault 环境的联调验证
- 基于样机的最终联调验证（当前已由 OpenBMC / iDRAC / iLO / XCC 脱敏夹具回归覆盖）
