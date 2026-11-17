#!/usr/bin/env bash
P=http://127.0.0.1:5173/api
echo "=== 服务商详情（布隆预热后应 200）==="
curl -s "$P/v1/shops/1" --max-time 10 | head -c 200; echo
echo "=== 时段列表 ==="
curl -s "$P/v1/slots?shopId=1" --max-time 10 | head -c 400; echo