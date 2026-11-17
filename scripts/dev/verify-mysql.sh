#!/usr/bin/env bash
B=http://localhost:8081
echo "=== 服务商列表（真实 MySQL）==="
curl -s "$B/api/v1/shops?page=1&size=10" --max-time 10 | head -c 250; echo
echo "=== 详情 ==="
curl -s "$B/api/v1/shops/1" --max-time 10 | head -c 180; echo
echo "=== 登录（触发 BCrypt 升级并写 MySQL）==="
curl -s -X POST "$B/api/v1/auth/login" -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' --max-time 10 | head -c 100; echo
echo "=== 时段 ==="
curl -s "$B/api/v1/slots?shopId=1" --max-time 10 | head -c 200; echo