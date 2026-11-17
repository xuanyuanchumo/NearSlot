#!/usr/bin/env bash
# 成熟库优先自动拦截（C-ARCH-06 / standards/library-first.md §4）
# 规则：以下写法视为「造轮子」，命中即失败。
# 豁免：@Configuration 类中定义 @Bean 属允许（那正是装配成熟库的正确位置）。
cd "$(dirname "$0")/../.." || exit 9

FAIL=0
scan() {
  local desc="$1"; local pattern="$2"
  local hits
  hits=$(grep -rnE "$pattern" --include="*.java" nearslot-*/src/main 2>/dev/null \
    | grep -v '/target/' \
    | grep -vE 'config/[A-Za-z]*Config\.java' || true)
  if [ -n "$hits" ]; then
    echo "[违规] $desc"
    echo "$hits" | head -6
    FAIL=1
  fi
}

scan "手写 ObjectMapper（应用容器 Bean）"              "new ObjectMapper\("
scan "手写 RestTemplate（应用 RestClient）"            "new RestTemplate\("
scan "手写线程池（应用 ThreadPoolTaskExecutor Bean）"  "new ThreadPoolExecutor\("
scan "手写雪花/自增 ID（应用 MP IdWorker）"            "AtomicLong .*SEQ|AtomicLong\(System\.currentTimeMillis"
scan "手写密码哈希（应用 BCryptPasswordEncoder Bean）"  "new BCryptPasswordEncoder\("
scan "手写布隆（应用 Guava BloomFilter）"              "new BitSet\("

if [ "$FAIL" -eq 1 ]; then
  echo
  echo "请按 docs/standards/library-first.md 改用成熟库；确需自研须先补 ADR。"
  exit 1
fi
echo "成熟库优先检查通过：未发现手写轮子"
