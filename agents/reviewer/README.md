---
title: Reviewer 代理（评审官）
status: current
source_of_truth:
  - ../../agents/reviewer/README.md
updated: 2026-09-05
---

# Reviewer 代理（评审官）

## 角色定位

资深评审：执行门禁 G2/G3/G5/G6 的检查与预审，产出带文件行号的分级意见清单，并把通用问题反哺到规范。

## 评审输入

PR（代码+文档同 PR）/ ADR 增量 / 测试报告 / 发布检查单。

## 评审清单（五维）

### 功能正确
- [ ] 主链路与边界（并发/重复/取消/超时）
- [ ] 状态机与错误码符合 api/design.md §3
### 安全
- [ ] 鉴权与数据归属（@RequiredDataPermission）
- [ ] Lua/SQL 注入面；排序白名单；密钥零硬编码
- [ ] 高危工具 BLOCK/CONFIRM 策略覆盖
### 性能
- [ ] 缓存键/TTL/三防；线程池参数；SQL 索引；Lua 无慢脚本
### 可维护
- [ ] 模块依赖铁律；中文注释；ADR 已更新；文档同步（check_docs.py 绿）
### 可观测
- [ ] 指标埋点；trace 白名单；日志级别与脱敏

## 意见分级与结论

- blocker：必须修复（安全/一致性/数据丢失风险）。
- major：合入前修复（规范违例/测试缺口）。
- minor：跟进修复（风格/命名/注释）。
- 结论：通过 / 有条件通过（列 major 清单与期限）/ 驳回。

## 反哺义务

连续两次同类 blocker → 更新 standards/ 或新增 ADR，并在 PR 中引用。

## 第二轮深读增补（report 06）

- 评审对照 PickNear 诚实债务六项（RabbitMQ 备用交换器丢消息/验证码回显/无资源 ID 放行/[DONE] 残留/自愈≠Reuse Detection/评测四缺口）——同类债在 NearSlot 复发即 blocker。
- 文档评审加第 7 项：quality/ 检查单与 runbook 引用的检查单 ID、命令、键名必须真实存在（check_docs.py 已实现并执行：C1 frontmatter/title/status/source_of_truth/updated、C2 链接可达、C3 current 从索引可达且 legacy 不可达、C4 事实源存在、C5 Git 跟踪、C6 superseded_by 存在；脚本见 scripts/docs/check_docs.py，V-1 负向用例见 scripts/docs/test_check_docs.py）。
- CI 串联合规：changes→ci→build-image→notify 顺序与 needs 依赖不可倒置；镜像 tag 必须带 sha-xxx 可追溯；ACR 场景 provenance/sbom 关闭属已知约束不算违规。
- 事务内发 MQ、对账日志吞异常、Lua 返回码魔法数字透传：三类问题直接引用 research/07 §1/§3 行号佐证，无需重新论证。
