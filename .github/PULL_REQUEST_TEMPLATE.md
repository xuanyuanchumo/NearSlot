## 背景

<!-- 关联 backlog 编号（BL-xxx-NN）、需求编号（FR-x.y）或 ADR 编号。没有编号说明为什么本次必须做。 -->

## 变更点

<!-- 逐条列出：新增/修改了哪些能力，涉及哪些模块/接口/表。多模块改动请说明是否可拆分。 -->

## 自检清单（缺一不可）

- [ ] 已阅读 `AGENTS.md` / `docs/指导核心.md` / `docs/standards/git.md`
- [ ] 本次任务在 `docs/planning/backlog.md` 有编号（或已新增一条）
- [ ] 没有新增硬编码业务策略值（ADR-030）；新增配置项已进 `nearslot.*` 且有绑定测试
- [ ] 没有跨模块直接依赖（C-ARCH-01）；跨域数据走 `common/api` 契约
- [ ] 文档与代码同 PR：ADR / 报告 / FR 状态表 / backlog **全部已更新**
- [ ] `docs/planning/backlog.md` 已完成项已改状态并追加"完成记录"行
- [ ] 未完成项已登记 backlog（不允许只写在报告末尾）

## 测试证据（必填，不接受"应该没问题"）

```
bash scripts/dev/build.sh -DskipTests      # 必须 clean
bash scripts/dev/verify_xxx.sh              # E2E：PASS=n FAIL=0
mvn -B -o clean test                        # BUILD SUCCESS
python scripts/docs/check_docs.py docs      # 通过
```

- 行覆盖率：__%（门槛 80，CI 会失败拦截）
- 真实中间件验证：MySQL / Redis / RabbitMQ 版本：______
- 涉及并发/一致性：写出实际观测到的数字（如 100 并发抢 1 唯一成功）

## 影响面

- [ ] 数据库变更（迁移脚本 `sql/NN_*.sql`，**幂等**，空库可重放）
- [ ] 对外接口变更（破坏性变更必须升 `/api/v{N+1}`，见 breaking-change-policy.md）
- [ ] 性能敏感路径（说明压测条件与 P99，见 performance/guide.md §6）
- [ ] 安全敏感（鉴权/越权/注入/文件上传）
- [ ] 配置项新增（列出键名与默认值）

## 回滚方案

<!-- 数据/接口/配置各自的回滚步骤；不可回滚的必须说明原因。 -->
