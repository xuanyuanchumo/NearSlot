import re
p='architecture/agent-module-design.md'
s=open(p,encoding='utf-8').read()
rows={
  'G1':'✅ 已闭合（BaseCode 502xx 段已加：AGENT_BLOCKED/NEED_CONFIRM/CONFIRM_EXPIRED/TOOL_LIMIT/PLAN_EMPTY/UPSTREAM_ERROR）',
  'G2':'✅ 已闭合（ADR-023 Spring AI 2.0.1 + DashScope 已登记）',
  'G3':'✅ 已闭合（5 个契约接口 + provider 实现 + E2E 实测）',
  'G4':'✅ 已闭合（ADR-025 登记 L2 澄清闸为 NearSlot 新增）',
  'G5':'✅ 已闭合（sql/8 补 seq/plan_json/trace_id，agent_message 现 9 列）',
  'G6':'✅ 已闭合（docs/README.md architecture 行已登记）',
}
for k,v in rows.items():
    s=re.sub(r'(\| '+k+r' \|[^|]*\|[^|]*\|[^|]*\| )[^|]*(\|)', lambda m: m.group(1)+v+' '+m.group(2), s)
s=s.replace('## 11. 已知缺口（开工前必须闭合）','## 11. 已知缺口（2026-11-17 已全部闭合）')
open(p,'w',encoding='utf-8').write(s)
print('updated')