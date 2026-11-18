#!/usr/bin/env bash
# 初始化本地测试库（ADR-032：中间件统一 Docker）
# 评审 E-001：原用 sql/3_*.sql sql/4_*.sql 旧数字 glob，03~13 全部跳过。
# 改为 find | sort，按两位零填充顺序应用全部迁移。
set -euo pipefail
cd "$(dirname "$0")/../.."

apply_all() {
  local db="$1"
  for f in $(find sql -maxdepth 1 -name '*.sql' | sort); do
    echo "  applying $f"
    docker exec -i nearslot-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < "$f" >/dev/null
  done
}

echo "=== 建库 + 全部迁移 ==="
apply_all nearslot
echo "=== 表清单 ==="
docker exec nearslot-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" nearslot -N -e "SHOW TABLES"' | head -30
