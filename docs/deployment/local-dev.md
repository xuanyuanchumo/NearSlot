---
title: 本地开发与容器编排
status: current
source_of_truth:
  - local-dev.md
updated: 2026-11-16
---

# 本地开发与容器编排

> 开发模式：**中间件跑在容器，前后端跑在本地**——改动即时生效，不必每次重建镜像。

## 1. 一键启动

```bash
cp .env.example .env      # 首次：填写密钥
bash scripts/dev/up.sh    # 启动 MySQL / Redis / RabbitMQ
bash scripts/dev/status.sh# 查看状态与端口

# 后端（本地 8081）
mvn -pl nearslot-app -am spring-boot:run -Dspring-boot.run.profiles=dev

# 前端（本地 5173，代理 /api → 8081）
cd nearslot-web && vp dev
```

访问：
- 前端 http://127.0.0.1:5173
- 接口文档 http://localhost:8081/doc.html
- 契约 JSON http://localhost:8081/v3/api-docs
- RabbitMQ 控制台 http://localhost:45673

## 2. 端口规划（避开常见占用）

| 服务 | 容器端口 | 说明 |
|---|---|---|
| MySQL | 43306 | 高于 32768 段容易冲突，故用 4xxxx |
| Redis | 46379 | |
| RabbitMQ AMQP | 45672 | |
| RabbitMQ 控制台 | 45673 | |
| 后端（本地） | 8081 | dev profile |
| 前端（本地） | 5173 | Vite+ dev |

## 3. 脚本清单

| 脚本 | 作用 |
|---|---|
| `scripts/dev/up.sh` | 启动中间件（幂等） |
| `scripts/dev/down.sh` | 停止中间件（保留数据卷） |
| `scripts/dev/reset.sh` | 停止 + 删卷 + 清构建产物（彻底重置） |
| `scripts/dev/status.sh` | 状态与端口探测 |

## 4. 编排文件

| 文件 | 用途 |
|---|---|
| `docker-compose.infra.yml` | **本地开发**：只跑中间件（MySQL/Redis/RabbitMQ） |
| `docker-compose.yml` | **联调/生产**：全栈（含 nearslot-app + nearslot-web/Nginx） |

## 5. 及时清理（重要）

开发过程中产生的临时资源必须随手清理，避免堆积：

```bash
# 1) 停止本地应用（IDE 或命令行启动的 java）
taskkill /PID <pid> /F          # Windows
pkill -f nearslot-app           # Git Bash

# 2) 停掉中间件容器
bash scripts/dev/down.sh

# 3) 彻底重置（数据卷 + 构建产物）
bash scripts/dev/reset.sh

# 4) 清理临时验证容器（按名字前缀）
docker rm -f $(docker ps -aq --filter "name=nearslot") 2>/dev/null

# 5) 清理悬空镜像与构建缓存（谨慎，影响全局）
docker image prune -f
```

> 约定：验证性容器统一命名 `nearslot-*`，验证结束立即 `docker rm -f`；不保留临时 profile 容器。

## 6. 前端工具链

前端用 **Vite+**（ADR-017）：

```bash
cd nearslot-web
vp install     # 安装依赖
vp dev         # 开发服务器
vp check       # 格式化 + lint + 类型检查（CI 同款）
vp build       # 生产构建
vp test run    # 单测
```
