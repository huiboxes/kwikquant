# syntax=docker/dockerfile:1
# -----------------------------------------------------------------------------
# Production image for the KwikQuant backend.
#
# Multi-stage: build the executable jar, then run it on a JRE as a non-root user.
# The runtime also carries the docker CLI (DockerWorkerManager orchestrates worker
# containers via the mounted host socket). prod backtest runs in the isolated worker
# image (DockerBacktestRunner), so the app image carries no python venv. Layered
# extraction keeps dependency layers cached across builds.
#
# Build from the repo root:
#   docker build -f docker/Dockerfile -t kwikquant-app .
#
# No secrets are baked in. ALL secrets are injected at runtime via env.
# -----------------------------------------------------------------------------

# ---- Stage 1: build ----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Warm the dependency cache on the POM first (better layer caching).
COPY pom.xml .
COPY src/ src/

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q clean package -DskipTests

# Extract Spring Boot layers for cache-friendly copying into the runtime image.
WORKDIR /build/target
RUN java -Djarmode=tools -jar kwikquant-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination extracted

# ---- Stage 2: runtime --------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root runtime user + curl for HEALTHCHECK + docker CLI for DockerWorkerManager.
# docker CLI 从官方 docker:cli 镜像拷(静态二进制,不装 apt 源,镜像增量小);app 容器挂宿主
# /var/run/docker.sock + group_add DOCKER_GID 编排 worker(DockerWorkerManager ProcessBuilder 调 docker run)。
# prod 回测容器化(DockerBacktestRunner 独立 worker 镜像执行),subprocess 路径已废,故 app 镜像不装
#   python3/venv(缩小镜像、降攻击面);dev/test 仍可用 subprocess 回退(application-dev.yaml)。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system kwik && useradd --system --gid kwik --no-create-home kwik
COPY --from=docker:cli /usr/local/bin/docker /usr/local/bin/docker

# Copy layers least- to most-frequently-changing for better caching.
COPY --from=build --chown=kwik:kwik /build/target/extracted/dependencies/ ./
COPY --from=build --chown=kwik:kwik /build/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=kwik:kwik /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=kwik:kwik /build/target/extracted/application/ ./

USER kwik
EXPOSE 8080

# Container-friendly heap sizing (respect cgroup limits).
# JAVA_TOOL_OPTIONS 由 JVM 原生读取,不依赖 entrypoint shell 是否正确展开变量。
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# SIGTERM 优雅停机(Spring context close + HikariPool 释放);exec-form 保证信号直达 PID 1。
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
