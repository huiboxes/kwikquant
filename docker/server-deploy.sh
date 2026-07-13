#!/bin/bash
# =============================================================================
# server-deploy.sh — 服务器 self-build(push github → Actions SSH 触发)
# 纯本地构建:首次 clone 仓库 → git fetch → checkout commit → docker build → up
# 服务器需能访问 github(public repo 直 clone;private 需 PAT/deploy key)
# =============================================================================
set -euo pipefail
COMMIT="${1:?usage: server-deploy.sh <commit-sha>}"
DEPLOY="${DEPLOY_PATH:-/opt/kwikquant}"
REPO="$DEPLOY/repo"
LOCK=/var/lock/kwikquant-deploy.lock
mkdir -p "$(dirname "$LOCK")" "$DEPLOY"
exec 9>"$LOCK"
flock -n 9 || { echo "[deploy] 另一部署进行中,退出"; exit 0; }

# 首次 clone(public repo 直 clone;private 需服务器 git 配 PAT/deploy key)
if [ ! -d "$REPO/.git" ]; then
  echo "[deploy] 首次 clone 仓库 → $REPO"
  git clone https://github.com/huiboxes/kwikquant.git "$REPO"
fi

cd "$REPO"
echo "[deploy] git fetch + checkout $COMMIT"
git fetch --all --tags
git checkout "$COMMIT" 2>/dev/null || { echo "[deploy] ✗ commit $COMMIT 不存在"; exit 1; }

# 同 commit noop
CURRENT=$(docker inspect kwikquant-app --format '{{.Config.Image}}' 2>/dev/null | sed 's/.*://' || echo "")
if [ "$CURRENT" = "$COMMIT" ]; then echo "[deploy] 已部署 $COMMIT,noop"; exit 0; fi

echo "[deploy] 服务器打包 $COMMIT(maven build + jar,几分钟)"
docker build -f docker/Dockerfile -t "kwikquant-app:$COMMIT" .

cd "$DEPLOY"
# .env 首次手动建;compose 用 repo 的
export COMMIT APP_IMAGE="kwikquant-app:$COMMIT"
docker compose -f "$REPO/docker/docker-compose.prod.yml" --env-file .env up -d

echo "[deploy] 等就绪..."
for i in $(seq 1 40); do
  if curl -fsS http://localhost:8080/actuator/health/readiness >/dev/null 2>&1; then
    echo "[deploy] 就绪 ✓ ($COMMIT)"; exit 0
  fi
  sleep 3
done
echo "[deploy] ✗ 健康超时,看 docker logs kwikquant-app" >&2; exit 1
