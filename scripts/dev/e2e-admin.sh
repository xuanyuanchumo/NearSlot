#!/usr/bin/env bash
B=http://localhost:48081
echo "=== 1) 无 token 访问内部接口（应 403）==="
curl -s -o /dev/null -w "HTTP=%{http_code}\n" -X POST $B/api/v1/internal/reconcile/run --max-time 15
echo "=== 2) 错误 token（应 403）==="
curl -s -o /dev/null -w "HTTP=%{http_code}\n" -X POST $B/api/v1/internal/reconcile/run -H "X-Admin-Token: wrong" --max-time 15
echo "=== 3) 正确 token（应 200 并执行对账）==="
curl -s -X POST $B/api/v1/internal/reconcile/run -H "X-Admin-Token: devadmintoken" --max-time 30 | head -c 200; echo