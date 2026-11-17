#!/usr/bin/env bash
cd "D:/Projects/TraeProjects/Studying" || exit 9
echo "=== 1) 前端 Vite+ 工具链 ==="
(cd NearSlot/nearslot-web && ./node_modules/.bin/vp check 2>&1 | tail -2)
echo "--- build ---"
(cd NearSlot/nearslot-web && ./node_modules/.bin/vp build > /tmp/wb.log 2>&1; echo "BUILD_EXIT=$?")
[ -f NearSlot/nearslot-web/dist/index.html ] && echo "dist/index.html OK"
echo "--- test ---"
(cd NearSlot/nearslot-web && ./node_modules/.bin/vp test run 2>&1 | grep -E "Test Files|Tests ")
echo "=== 2) 后端 Spring Boot 4 ==="
(cd NearSlot && mvn -B -q clean test > /tmp/m.log 2>&1; echo "MVN_EXIT=$?")
grep -o "<spring-boot.version>[^<]*" NearSlot/pom.xml
echo "=== 3) compose ==="
python -c "import yaml;print(sorted(yaml.safe_load(open('NearSlot/docker-compose.yml',encoding='utf-8'))['services']))"
echo "=== 4) 文档 ==="
python NearSlot/scripts/docs/check_docs.py NearSlot/docs --repo-root . | tail -1
echo "=== DONE ==="
