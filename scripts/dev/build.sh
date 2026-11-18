#!/usr/bin/env bash
# 构建产物（**总是 clean**）
#
# 为什么强制 clean：本项目多次踩到"改了代码、跑了构建、行为没变"——
# 编辑工具改写文件时 mtime 未前进，Maven 的增量编译判定 class 仍新鲜而跳过编译。
# 排查成本极高（会误以为是逻辑问题）。因此本地/CI 一律 clean 后再 package。
set -euo pipefail
cd "$(dirname "$0")/../.."
mvn -B -o clean package "$@"
echo "BUILD OK: nearslot-app/target/*.jar（已强制 clean，产物与源码一致）"
