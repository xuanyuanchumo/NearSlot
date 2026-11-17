# NearSlot 后端镜像（deployment/guide.md §2：多阶段 + 分层 + 非 root）
# 说明：镜像仅由 CI 构建推送，禁止本地 build 上生产（ADR-013）
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY nearslot-common/pom.xml nearslot-common/
COPY nearslot-framework/pom.xml nearslot-framework/
COPY nearslot-module-auth/pom.xml nearslot-module-auth/
COPY nearslot-module-shop/pom.xml nearslot-module-shop/
COPY nearslot-module-appointment/pom.xml nearslot-module-appointment/
COPY nearslot-module-order/pom.xml nearslot-module-order/
COPY nearslot-module-agent/pom.xml nearslot-module-agent/
COPY nearslot-app/pom.xml nearslot-app/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY . .
RUN mvn -B -q clean package -DskipTests \
    && java -Djarmode=tools -jar nearslot-app/target/nearslot-app-*.jar extract --destination /build/extracted

FROM eclipse-temurin:21-jre AS runtime
RUN groupadd -g 1000 appuser && useradd -u 1000 -g appuser -s /bin/sh -m appuser
WORKDIR /app
COPY --from=builder --chown=appuser:appuser /build/extracted/ ./

ENV JAVA_OPTS="-XX:InitialRAMPercentage=50 -XX:MaxRAMPercentage=80"
ENV SPRING_PROFILES_ACTIVE=prod

USER appuser
EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q --spider http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
