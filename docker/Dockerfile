# syntax=docker/dockerfile:1
# -----------------------------------------------------------------------------
# Production image for the KwikQuant backend.
#
# Multi-stage: build the executable jar, then run it on a JRE as a non-root user.
# The runtime also carries the docker CLI (DockerWorkerManager orchestrates worker
# containers via the mounted host socket) and a python venv + worker sources
# (backtest subprocess, PythonSubprocessBacktestRunner). Layered extraction keeps
# dependency layers cached across builds.
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
# python3(+venv):prod 回测子进程(PythonSubprocessBacktestRunner,BacktestRunner 唯一实现)在 app
#   容器内执行;独立 venv(/opt/venv)避开 PEP 668 externally-managed。prod 默认
#   kwikquant.worker.python-command=/opt/venv/bin/python(application-prod.yaml),kwik 用户可直接执行。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl python3 python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system kwik && useradd --system --gid kwik --no-create-home kwik \
    && python3 -m venv /opt/venv
COPY --from=docker:cli /usr/local/bin/docker /usr/local/bin/docker

# 回测子进程依赖(httpx 等;numpy/pandas 为用户策略可选 import,一并装入防运行时缺包)。
# requirements 低频变更,独立成层保缓存;pyproject requires-python>=3.11 显式断言 fail-fast。
COPY requirements-worker.txt ./
RUN /opt/venv/bin/python -c 'import sys; assert sys.version_info >= (3, 11), sys.version' \
    && /opt/venv/bin/pip install --no-cache-dir -r requirements-worker.txt

# Copy layers least- to most-frequently-changing for better caching.
COPY --from=build --chown=kwik:kwik /build/target/extracted/dependencies/ ./
COPY --from=build --chown=kwik:kwik /build/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=kwik:kwik /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=kwik:kwik /build/target/extracted/application/ ./

# 回测子进程源码:RealSubprocessExecutor 设 PYTHONPATH=<user.dir>=/app 并执行相对路径
# kwikquant.worker.script(prod 默认 kwikquant_worker/worker_server.py),故源码须落在 /app 下。
COPY --chown=kwik:kwik kwikquant/ ./kwikquant/
COPY --chown=kwik:kwik kwikquant_worker/ ./kwikquant_worker/

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
