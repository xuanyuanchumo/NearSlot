#!/usr/bin/env bash
cd "D:/Projects/TraeProjects/Studying" || exit 9
echo "=== V-1 文档四检（pre-commit 模拟）==="
python NearSlot/scripts/docs/check_docs.py NearSlot/docs --repo-root . || exit 1
echo "=== V-1 单元负向测试 ==="
python NearSlot/scripts/docs/test_check_docs.py || exit 1
echo "=== V-4 密钥扫描 ==="
bash NearSlot/scripts/security/scan_secrets.sh || exit 1
echo "=== CI YAML 语法 ==="
python -c "import yaml,sys; yaml.safe_load(open('NearSlot/.github/workflows/ci-cd.yml',encoding='utf-8')); print('YAML OK')" || echo "PyYAML 不可用，跳过"
echo "=== ALL_GREEN ==="
