#!/usr/bin/env bash
echo "等待在途窗口(12s)过期..."
sleep 14
echo "=== 再次对账（在途窗口已过）==="
curl -s -X POST http://localhost:48081/api/v1/internal/reconcile/run -H "X-Admin-Token: devadmintoken" --max-time 30 | head -c 120; echo
sleep 2