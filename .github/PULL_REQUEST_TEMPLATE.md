<!--
请按照以下结构填写 PR 描述。CI/CD、测试与高风险改动的单一事实来源是：
  - documentation/CI_CONTRACT.md
  - documentation/CI_FAILURE_PATTERNS.md
-->

## Summary

<!-- 1-3 句话说明：改了什么、为什么改。不要复述 diff，而是讲清动机和效果。 -->

-
-

## Test Plan

<!-- 勾选本 PR 已经在本地执行过的验证。请至少覆盖受影响的子系统。 -->

- [ ] Core: `cd core && ./gradlew check --no-daemon`
- [ ] Satellite: `cd satellite && go test ./... -count=1`
- [ ] Frontend: `cd frontend && npm test && npm run lint && npm run build`
- [ ] `./scripts/check_ci_contract.sh`（高风险改动必跑）
- [ ] 其他（请说明）：

## CI Contract 自检

参见 [CI_CONTRACT.md](../documentation/CI_CONTRACT.md) 的高风险改动矩阵。如果本 PR 涉及以下任一内容，请勾选并说明已执行的验证：

- [ ] `.github/workflows/**`
- [ ] `core/src/main/resources/application*.properties`
- [ ] `core/src/test/**`
- [ ] `load-test` 相关
- [ ] gRPC / TLS / Kafka / Redis / DB / scheduler / health probe 相关代码
- [ ] 以上均不涉及

## 风险评估

<!-- 回滚代价、影响面、是否需要特殊上线顺序、是否需要数据迁移配合 -->

- 回滚难度：<!-- easy / medium / hard -->
- 影响面：<!-- 单模块 / 跨模块 / 跨子系统 -->
- 数据迁移：<!-- 无 / 有（请说明） -->
- 上线注意事项：

## 关联

<!-- 关联 Issue、设计文档、之前相关 PR 等 -->

-
