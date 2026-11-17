---
title: Docker 镜像加速与拉取参考（华为云 SWR）
status: current
source_of_truth:
  - docker-mirror.md
updated: 2026-11-17
---

# Docker 镜像加速与拉取参考

> **背景**：本项目所需镜像（mysql / redis / rabbitmq / apache-kafka 等）从 Docker Hub 直连极慢甚至失败。
> 本机已配置 7 个 registry mirror，但实测多数不可用（见 §2）。**华为云 SWR 加速源实测可用**，作为首选方案记录于此。

## 1. 推荐做法（华为云 SWR）

### 1.1 拉取

```bash
docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/mysql:8.0
```

URL 规律：`swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/<镜像名>:<tag>`

### 1.2 重命名为短标签（重要）

```bash
docker tag swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/mysql:8.0 mysql:8.0
```

**为什么必须重命名**：`docker-compose.yml` / `docker-compose.infra.yml` 里写的是 `image: mysql:8.0`。
若只有长地址标签，compose 会再次尝试从 Hub 拉取并卡住。重命名后本地已有 `mysql:8.0`，compose 直接命中本地缓存。

### 1.3 验证

```bash
docker images | grep mysql
# 期望看到两行（同一镜像 ID，两个标签）：
#   mysql:8.0                                             1.06GB
#   swr.cn-north-4.myhuaweicloud.com/.../mysql:8.0        1.06GB
```

### 1.4 常用镜像的 SWR 地址

```bash
# Redis 7
docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/redis:7-alpine
docker tag  swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/redis:7-alpine redis:7-alpine

# RabbitMQ 3.13（含管理台）
docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/rabbitmq:3.13-management-alpine
docker tag  swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/rabbitmq:3.13-management-alpine rabbitmq:3.13-management-alpine

# Nginx
docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/nginx:1.27-alpine
docker tag  swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/nginx:1.27-alpine nginx:1.27-alpine
```

## 2. 镜像源实测结果（2026-11-17，本机）

对 `https://<mirror>/v2/` 做连通性探测：

| 镜像源 | 结果 | 判定 |
|---|---|---|
| `mirrors.aliyun.com` | HTTP 404 | ❌ 不可用 |
| `docker.mirrors.ustc.edu.cn` | 连接超时(000) | ❌ |
| `mirror.ccs.tencentyun.com` | 连接超时(000) | ❌（该源仅腾讯云内网可用） |
| `hub-mirror.c.163.com` | 连接超时(000) | ❌ |
| `docker.1ms.run` | HTTP 401 | 🟡 服务在线但需认证，拉取极慢（实测数十分钟未完成） |
| `docker-0.unsee.tech` | HTTP 401 | 🟡 同上 |
| `docker.m.daocloud.io` | HTTP 401 | 🟡 同上 |
| `registry-1.docker.io`（官方） | 连接超时(000) | ❌ |
| **`swr.cn-north-4.myhuaweicloud.com`** | **✅ 拉取成功（1.06GB）** | **✅ 首选** |

> 结论：**daemon.json 里配的 mirror 列表对 mysql 这类大镜像基本无效**，直接用 SWR 全限定地址拉取最可靠。

## 3. 备选方案：本地已安装的服务

本机已装 **MySQL 8.0.34**（`D:/MySQL/MySQL Server 8.0`），可用于免容器验证：

```bash
bash scripts/dev/init-local-mysql.sh    # 初始化数据目录（首次）
# 启动 mysqld（后台）
"D:/MySQL/MySQL Server 8.0/bin/mysqld" --basedir="D:/MySQL/MySQL Server 8.0" \
  --datadir="D:/MySQL/nearslot-data" --port=3306 --console
bash scripts/dev/init-mysql-schema.sh   # 建库建表（15 张）
bash scripts/dev/seed-mysql.sh          # 种子数据
```

适用场景：镜像拉取受阻、需要快速验证；**不足**：与容器环境不完全同构（无 init 脚本挂载、端口与容器版不同）。

## 4. 与本项目 compose 的衔接

| 场景 | 用法 |
|---|---|
| 标准开发（推荐） | `bash scripts/dev/up.sh` → 走 `docker-compose.infra.yml`（MySQL 43306 / Redis 46379 / RabbitMQ 45672） |
| 镜像缺失时 | 先按 §1 从 SWR 拉取并 `docker tag` 为短名，再执行 `up.sh` |
| 免容器快速验证 | 按 §3 用本机 MySQL 3306 |

**端口差异注意**：容器版 MySQL 映射到 **43306**，本机实例是 **3306**。
`application-verify.yaml` 通过 `${MYSQL_PORT:3306}` 参数化，两种方式都支持。

## 5. 清理约定

```bash
# 删除长地址标签（保留短名，节省 listings 混乱）
docker rmi swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/mysql:8.0

# 清理验证用容器
docker rm -f nearslot-mysql nearslot-redis nearslot-rabbit 2>/dev/null

# 彻底重置（含数据卷）
bash scripts/dev/reset.sh
```

## 6. 记录原则

遇到"镜像拉不动"时，**优先试 SWR 全限定地址**，而不是反复调 daemon.json 的 mirror 列表（实测无效）。
拉取后**务必 docker tag 成短名**，否则 compose 仍会回落到 Hub。
