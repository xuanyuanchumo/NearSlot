#!/usr/bin/env bash
MYSQL_HOME="D:/MySQL/MySQL Server 8.0"
DATA="D:/MySQL/nearslot-data"
echo "=== 检查数据目录 ==="
if [ ! -d "$DATA" ]; then
  echo "初始化数据目录..."
  mkdir -p "$DATA"
  "$MYSQL_HOME/bin/mysqld" --initialize-insecure --basedir="$MYSQL_HOME" --datadir="$DATA" 2>&1 | tail -5
  echo "INIT_DONE"
else
  echo "已存在: $DATA"
fi