# Redfish + 零接触纳管任务清单

## 已完成

- [x] 将 `satellite/pkg/redfish` 重构为适配器式结构
- [x] 增加 OpenBMC 基线适配器
- [x] 增加可导入 JSON Redfish 模板的加载与执行逻辑
- [x] 保持 `Collector` 对外 API 兼容
- [x] 为 `DiscoveredDevice` 增加 claim / auth 规划字段
- [x] 新增 `CredentialProfile` 数据模型与 REST 管理接口
- [x] 新增 `DeviceClaimPlanner` 自动匹配逻辑
- [x] 将 discovery 上报、网络扫描、手工录入接入 claim 规划
- [x] 将默认网络扫描端口补齐到 `443`，使 Redfish 发现不再只依赖 `623`
- [x] 补充 Redfish 模板与 claim 规划相关单元测试
- [x] 将 `authStatus / claimStatus / recommendedRedfishTemplate` 暴露到 Discovery 前端页面
- [x] 增加最小可用的 `Credential Profiles` 前端管理页
- [x] 为 Discovery 页面增加 claim/auth 检索与筛选能力
- [x] 在 `Credential Profiles` 页面补充编辑能力
- [x] 增加 `secret ref` 解析器，并将密钥可用性纳入 claim readiness 判断
- [x] 增加 `CredentialProfile` secret ref 校验接口与前端校验按钮
- [x] 为 OpenBMC / iDRAC / iLO / XCC 增加模板样例库
- [x] 基于厂商/型号提示自动推荐 Redfish 模板
- [x] 为 `Credential Profiles` 页面接入模板目录 API 和模板选择提示
- [x] 在 `Credential Profiles` 页面补充批量导入能力
- [x] 增加真实 Redfish claim 验证执行器和 `/api/discovery/{id}/claim` 接口
- [x] 在 Discovery 页面增加 claim 执行动作入口
- [x] 给 `CredentialProfile` 接入最小可用的 HashiCorp Vault KV 客户端
- [x] 将 `vault://` secret ref 纳入真实 readiness / validate / claim 流程
- [x] 在 claim 成功后增加标准 Redfish 托管账号创建能力
- [x] 为 `CredentialProfile` 增加托管账号 secret ref 和角色配置
- [x] 在重新执行 claim 时，对已存在托管账号做标准 Redfish 密码/角色收敛
- [x] 接入 CMDB / 交付台账导入通道，补齐 bootstrap 凭据来源
- [x] 为外部导入凭据增加 `sourceType / externalRef / hostname/IP/MAC` 匹配字段

## 下一阶段

- [x] 增加独立触发或定时触发的平台 BMC 账号轮换（`BmcCredentialRotationService` + `@Scheduled` + `POST /{id}/rotate-credentials`）
- [x] 将 Vault 能力抽象成可替换的 Secret Manager 适配层（`SecretManagerClient` 接口，`VaultSecretClient` 实现）
- [x] 增加端到端测试覆盖 Discovery -> Claim -> Managed 的闭环（`DiscoveryClaimManagedFlowTest`）
- [x] 增加在线 CMDB bootstrap 同步框架（通用 JSON API + 手工/定时触发）
- [x] 对接真实企业 CMDB API 适配层（映射文件 + 分页 next link + 精确匹配字段转换）

## 风险点

- [x] 现有前端 `/api/nodes` 返回模型与 Node/Redfish 字段仍存在脱节（已修复：`NodeResource` 合并 Satellite + Node 返回完整字段）
- [x] 真实 BMC 厂商兼容性已补齐脱敏响应夹具回归（OpenBMC / iDRAC / iLO / XCC）
