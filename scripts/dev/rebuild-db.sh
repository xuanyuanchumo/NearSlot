#!/usr/bin/env bash
# 删库重建（破坏性变更 SOP；ADR-032：Docker 中间件）
# 评审 E-001：改用 find | sort 应用全部 01..13 迁移，不再用手写旧 glob。
set -euo pipefail
cd "$(dirname "$0")/../.."
MYSQL() { docker exec -i nearslot-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' "$@"; }

echo "=== 删库重建 ==="
docker exec nearslot-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS nearslot;"'
echo "=== 应用全部迁移（01..13）==="
for f in $(find sql -maxdepth 1 -name '*.sql' | sort); do
  echo "  applying $f"
  MYSQL < "$f" >/dev/null
done
echo "=== 种子数据 ==="
MYSQL nearslot < scripts/dev/seed_dev.sql >/dev/null
echo "=== 校验 ==="
docker exec nearslot-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" nearslot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\"nearslot\""'
