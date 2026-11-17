#!/usr/bin/env bash
cd "D:/Projects/TraeProjects/Studying" || exit 9
echo "=== compose YAML 解析 ==="
python - <<'PY'
import yaml
with open('NearSlot/docker-compose.yml',encoding='utf-8') as f: d=yaml.safe_load(f)
print('services:', list(d['services'].keys()))
PY
echo "=== 文档四检 ==="
python NearSlot/scripts/docs/check_docs.py NearSlot/docs --repo-root . | tail -2
echo "=== 测试总数 ==="
grep -h "Tests run:" NearSlot/nearslot-framework/target/surefire-reports/*.txt 2>/dev/null | tail -6
echo "=== VERIFY_DONE ==="
