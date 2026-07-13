#!/bin/bash
# =============================================================================
# server-deploy.sh — KwikQuant 竞速部署核心
#
# 用法:sudo docker/server-deploy.sh <commit-sha>
#
# 设计:路径 A(GitHub Actions push GHCR 镜像)与路径 B(服务器 post-receive
# 本地构建)并行触发本脚本。flock 串行化 + 同 commit noop 保证"谁快用谁":
#   1. 先到的:pull registry 或 local build → up -d → 完工
#   2. 后到的:运行容器已是同 commit → noop
# 路径 A 优先(超时 120s 等 CI push);超时/失败回退路径 B 本地构建。
# =============================================================================
set -euo pipefail

COMMIT="${1:?usage: server-deploy.sh <commit-sha>}"
DEPLOY="${DEPLOY_PATH:-/opt/kwikquant}"
# 改成你的 GHCR owner(通常同 GitHub 用户名/repo owner)
GHCR_REPO="${GHCR_REPO:-ghcr.io/huiboxes/kwikquant}"
LOCK=/var/lock/kwikquant-deploy.lock

mkdir -p "$(dirname "$LOCK")"

# 串行化:并发部署只跑一个,其他 noop
exec 9>"$LOCK"
if ! flock -n 9; then
  echo "[deploy] 另一部署进行中,本任务退出"
  exit 0
fi

# 比对运行容器 image tag == 目标 commit → noop
CURRENT=""
if docker inspect kwikquant-app >/dev/null 2>&1; then
  CURRENT=$(docker inspect kwikquant-app --format '{{.Config.Image}}' 2>/dev/null | sed 's/.*://' || echo "")
fi
if [ "$CURRENT" = "$COMMIT" ]; then
  echo "[deploy] 已部署 $COMMIT,noop"
  exit 0
fi

echo "[deploy] 部署 commit $COMMIT (当前=$CURRENT)"

IMAGE=""
# 路径 A:试 pull registry 镜像(超时 120s 等 GitHub Actions push 完成)
echo "[deploy] 试 pull $GHCR_REPO:$COMMIT (超时 120s)..."
if timeout 120 docker pull "$GHCR_REPO:$COMMIT" 2>/dev/null; then
  IMAGE="$GHCR_REPO:$COMMIT"
  echo "[deploy] 路径 A 命中 registry ✓"
else
  # 路径 B:本地构建(服务器 git checkout 的代码)
  echo "[deploy] registry 未就绪,回退本地构建(路径 B)"
  cd "$DEPLOY"
  git fetch --all >/dev/null 2>&1 || true
  git checkout "$COMMIT" 2>/dev/null || git checkout main
  docker build -f docker/Dockerfile -t "kwikquant-app:$COMMIT" .
  IMAGE="kwikquant-app:$COMMIT"
  echo "[deploy] 路径 B 本地构建完成 ✓"
fi

cd "$DEPLOY"
export COMMIT APP_IMAGE="$IMAGE"
docker compose -f docker-compose.prod.yml --env-file .env up -d

# 健康等待(start_period 60s,留 90s 兜底)
echo "[deploy] 等就绪..."
for i in $(seq 1 30); do
  if curl -fsS http://localhost:8080/actuator/health/readiness >/dev/null 2>&1; then
    echo "[deploy] 就绪 ✓ ($COMMIT)"
    exit 0
  fi
  sleep 3
done

echo "[deploy] ✗ 健康检查超时,看日志:docker logs kwikquant-app" >&2
exit 1
