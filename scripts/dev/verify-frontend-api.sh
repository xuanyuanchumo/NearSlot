#!/usr/bin/env bash
P=http://127.0.0.1:5173/api
echo "=== 服务商列表 /shops ==="
curl -s "$P/v1/shops?page=1&size=10" --max-time 10 | head -c 300; echo
echo "=== 服务商详情 /shops/1 ==="
curl -s "$P/v1/shops/1" --max-time 10 | head -c 200; echo
echo "=== 技师列表 /shops/1/technicians ==="
curl -s "$P/v1/shops/1/technicians" --max-time 10 | head -c 250; echo
echo "=== 时段列表 /slots?shopId=1 ==="
curl -s "$P/v1/slots?shopId=1" --max-time 10 | head -c 300; echo
echo "=== 登录 ==="
curl -s -X POST "$P/v1/auth/login" -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' --max-time 10 | head -c 100; echo