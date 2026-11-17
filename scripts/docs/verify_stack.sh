#!/usr/bin/env bash
cd "D:/Projects/TraeProjects/Studying" || exit 9
echo "=== 1) 后端全量构建（Spring Boot 4）==="
(cd NearSlot && mvn -B -q clean test > /tmp/mvn.log 2>&1; echo "MVN_EXIT=$?")
echo "=== 2) 前端产物 ==="
ls NearSlot/nearslot-web/dist/index.html >/dev/null 2>&1 && echo "dist/index.html OK" || echo "dist MISSING"
echo "=== 3) compose 服务 ==="
python -c "import yaml;d=yaml.safe_load(open('NearSlot/docker-compose.yml',encoding='utf-8'));print(sorted(d['services'].keys()))"
echo "=== 4) 文档四检 ==="
python NearSlot/scripts/docs/check_docs.py NearSlot/docs --repo-root . | tail -2
echo "=== 5) 技术栈版本 ==="
grep -o "<spring-boot.version>[^<]*" NearSlot/pom.xml
grep -o "<redisson.version>[^<]*" NearSlot/pom.xml
grep -o "\"typescript\": \"[^\"]*\"" NearSlot/nearslot-web/package.json
echo "=== DONE ==="
