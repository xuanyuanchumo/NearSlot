---
title: 部署与发布规范
status: current
source_of_truth:
  - ../../docs/deployment/guide.md
updated: 2026-11-16
---

# 部署与发布规范

## 1. 环境矩阵

| 环境 | 编排 | 数据 | 说明 |
|---|---|---|---|
| dev | 本机 + compose（mysql/redis/rabbitmq） | 初始化脚本 | 8081，热重载 |
| staging | compose（全栈） | 影子库 | 压测/验收 |
| prod | compose（单机起步）→ K8s | 正式库 | 8082，镜像仅 CI 构建 |

## 2. Dockerfile 规范（继承 PickNear 78 行模板）

- 多阶段：stage1 maven:3.9-temurin-21 builder（settings.xml 国内镜像 + dependency:go-offline 依赖层缓存 + -T 1C）；stage1 末 java -Djarmode=layertools extract 拆层。
- stage2 temurin:21-jre：先建 appuser(1000) 再 COPY --chown（避免 COPY 后 chown -R 重写整层）；分层 COPY dependencies / snapshot-dependencies / spring-boot-loader / application。
- HEALTHCHECK wget --spider /actuator/health（30s/5s/40s/3 retries）；JAVA_OPTS=-XX:InitialRAMPercentage=50 -XX:MaxRAMPercentage=80；ENTRYPOINT exec java JarLauncher。
- 禁止：镜像内打密钥、latest 手工推、本地 docker build 上生产（只允许 CI 构建推送）。

## 3. docker-compose 规范（ADR-013）

- 全部服务 mem_limit + healthcheck；depends_on 用 condition: service_healthy。
- 密钥全部走 .env：变量书写采用「美元符+花括号+VAR+:+?+提示」强制缺省语法（如 DB_PASSWORD 缺失即拒绝启动）；.env 不入库，提供 .env.example。
- 服务清单：mysql:8.0(1g) / redis:7-alpine(256m, appendonly yes) / rabbitmq:3-management(1g) / nearslot-app(1g) / nearslot-web nginx(128m，托管 SPA + 反代 /api)；命名卷数据三件 + 独立 bridge 网络。
- 端口映射统一 4xxxx 段避开常见占用；日志驱动 json-file 上限 50m×3。

## 4. CI/CD（继承 PickNear ci-cd.yml 结构）

```
changes 判定：纯文档变更（md/ 目录或 *.md）→ 跳过编译测试，只跑文档检查
docs 检查：check_docs.py（frontmatter/链接/事实源可达性）
ci：JDK21 temurin → mvn compile → mvn test（排除集成测试 tag）→ JaCoCo 覆盖率报告
integration（staging 触发）：Testcontainers 全量集成
build-image：仅 push master / 手动 → 构建推 ACR，tag latest + sha-xxxxxxx
notify：webhook 通知构建结果（未配置则跳过）
```

- 集成测试排除模式：-DexcludedGroups=integration 或显式 !类名（PickNear 排除 TokenTest/AgentTracerIntegrationTest/LangfuseSmokeTest 模式）。
- 镜像 provenance/sbom 按 ACR 支持情况开关；GHA 缓存 scope 按应用名。

## 5. 发布与回滚

- 发布：compose pull + up -d --no-build（PickNear 铁律）；K8s 期走 Argo 拉升 tag。
- 回滚：镜像 tag 双版本共存，回滚即切 tag；数据库回滚只允许向前修（新迁移脚本），禁止生产 drop。
- 观测接入：/actuator/prometheus 抓取 + Langfuse OTLP（白名单 ADR-011）；健康分 = 存活探针 + 依赖健康（rabbitmq health 按需开）。

## 6. 配置分层

- application.yaml（默认 dev）→ application-{profile}.yaml → 环境变量 → 启动参数；prod 由 SPRING_PROFILES_ACTIVE 指定，配置不落镜像。
- 日志：logback-spring.xml，级别按包六档可调（环境变量 LOG_LEVEL_MODULE 默认 INFO，PickNear 模式）。
