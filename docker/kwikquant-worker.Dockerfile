# kwikquant-worker Docker image (Wave 8 §3.5/§3.7)
#
# 镜像预装 kwikquant SDK + kwikquant_worker runtime。
# 容器安全加固 (§3.7):非 root 用户,只读根文件系统,内存/CPU 限制由 DockerWorkerManager 传入。
#
# 构建:
#   docker build -f docker/kwikquant-worker.Dockerfile -t kwikquant-worker:latest .
#
# 运行(由 Java DockerWorkerManager 触发,勿手工):
#   docker run --rm --user 1000:1000 --read-only --no-new-privileges \
#     --memory 512m --cpus 1.0 --network kwikquant-worker-net \
#     -e WORKER_SERVICE_TOKEN=... -e TASK_CONFIG_JSON=... -e KWIKQUANT_API_BASE=... \
#     kwikquant-worker:latest --mode=runner

FROM python:3.11-slim AS base

# 系统层最小化;--no-install-recommends 减少 attack surface
ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates libpq5 \
    && rm -rf /var/lib/apt/lists/*

# 非 root 用户(§3.7 --user 1000:1000)
RUN groupadd --system --gid 1000 kwik && \
    useradd --system --uid 1000 --gid kwik --home-dir /app --shell /usr/sbin/nologin kwik

WORKDIR /app

# 依赖层缓存:先装 requirements
COPY --chown=kwik:kwik requirements-worker.txt ./
RUN pip install --no-cache-dir -r requirements-worker.txt

# SDK + runtime 源码
COPY --chown=kwik:kwik kwikquant/ ./kwikquant/
COPY --chown=kwik:kwik kwikquant_worker/ ./kwikquant_worker/
COPY --chown=kwik:kwik pyproject.toml ./

# 允许运行时导入(--read-only rootfs 场景下把 /tmp 挂 tmpfs 保留可写)
ENV PYTHONPATH=/app

# /health 端口(§3.7)
EXPOSE 8081

USER kwik

ENTRYPOINT ["python", "-m", "kwikquant_worker.worker_server"]
CMD ["--mode=runner"]

# HEALTHCHECK 借助 stdlib(镜像无 curl);Docker 侧健康信号,与 Java 的 /health HTTP 独立
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import http.client,sys; c=http.client.HTTPConnection('127.0.0.1',8081,timeout=3); c.request('GET','/health'); sys.exit(0 if c.getresponse().status==200 else 1)"
