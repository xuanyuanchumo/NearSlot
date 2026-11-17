#!/usr/bin/env bash
echo "=== 测试各镜像源可达性（mysql:8.0 manifest）==="
for m in mirrors.aliyun.com docker.mirrors.ustc.edu.cn mirror.ccs.tencentyun.com hub-mirror.c.163.com docker.1ms.run docker-0.unsee.tech docker.m.daocloud.io registry-1.docker.io; do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 "https://$m/v2/" 2>/dev/null)
  echo "  $m -> HTTP=$code"
done