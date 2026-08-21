#!/bin/bash
# =============================================================================
# server-deploy-image.sh — 镜像 pull 部署(替代 server-deploy.sh 的服务器 self-build)
#
# 流程:git fetch+checkout tag → docker compose pull(app/frontend) → docker pull worker
#       → docker compose up -d → readiness 检查(40×3s) → 成功记 last-good-tag。
# 数据库迁移可能不可逆，失败时禁止盲目启动旧二进制；按发布恢复点人工处置。
#
# 用法:bash server-deploy-image.sh <tag>   # 如 v0.1.0
# 前置:服务器 docker login ghcr.io(私仓读权限,见 docs/deploy.md 5.2 节)。
#
# worker 镜像不在 compose 里(DockerWorkerManager 按需 docker run);这里预拉 :$TAG
# 避免策略首次启动才拉。worker 跟 $TAG 锁版本(防 :latest 与 app tag 错版);env KWIKQUANT_WORKER_IMAGE
# 传 app 容器覆盖 application-prod.yaml 默认,回滚时 worker 也跟 PREV_TAG 回滚。
# =============================================================================
set -euo pipefail
TAG="${1:?usage: server-deploy-image.sh <tag>}"
DEPLOY="${DEPLOY_PATH:-/opt/kwikquant}"
REPO="$DEPLOY/repo"
COMPOSE="$REPO/docker/docker-compose.prod.yml"
ENV_FILE="$DEPLOY/.env"
LAST_GOOD="$DEPLOY/.last-good-tag"
LOCK="$DEPLOY/.deploy.lock"
IMAGE_APP="ghcr.io/huiboxes/kwikquant:$TAG"
IMAGE_FRONTEND="ghcr.io/huiboxes/kwikquant-frontend:$TAG"
IMAGE_WORKER="ghcr.io/huiboxes/kwikquant-worker:$TAG"
# 无面板用户:export TLS_CERT_DIR=<证书目录> 自动起 edge 容器(:443+origin cert);有面板用户不设
PROFILE_ARGS=""
[ -n "${TLS_CERT_DIR:-}" ] && PROFILE_ARGS="--profile edge"
# app 容器挂宿主 docker.sock 编排 worker(DockerWorkerManager);kwik(1000) 加宿主 docker 组读 socket
export DOCKER_GID="$(getent group docker | cut -d: -f3 || echo 0)"
# worker 镜像随 tag 精确化(compose 传 app 容器覆盖 application-prod.yaml;防 :latest 与 app tag 错版)
export KWIKQUANT_WORKER_IMAGE="$IMAGE_WORKER"

on_deploy_error() {
  local exit_code=$?
  echo "[deploy] ✗ 部署命令失败(exit=$exit_code line=${BASH_LINENO[0]});未自动回滚应用或数据库" >&2
  exit "$exit_code"
}
trap on_deploy_error ERR

mkdir -p "$(dirname "$LOCK")" "$DEPLOY"
exec 9>"$LOCK"
flock -n 9 || { echo "[deploy] 另一部署进行中,退出"; exit 1; }

# 首次 clone(public repo 直 clone;private 需服务器 git 配 PAT/deploy key)
if [ ! -d "$REPO/.git" ]; then
  echo "[deploy] 首次 clone 仓库 → $REPO"
  git clone https://github.com/huiboxes/kwikquant.git "$REPO"
fi

cd "$REPO"
echo "[deploy] git fetch + checkout tag $TAG"
git fetch --all --tags
git checkout "$TAG" || { echo "[deploy] ✗ checkout $TAG 失败(确认 tag 存在 + repo 工作区干净;必要时 git reset --hard origin/$TAG)"; exit 1; }

COMPOSE="$REPO/docker/docker-compose.prod.yml"

# 部署前记当前 last-good 作回滚锚点(首次部署无锚点,失败只报错不回滚)
PREV_TAG=""
if [ -f "$LAST_GOOD" ]; then PREV_TAG="$(cat "$LAST_GOOD")"; fi

echo "[deploy] docker compose pull $TAG(app + frontend$([ -n "$PROFILE_ARGS" ] && echo ' + edge'))"
APP_IMAGE="$IMAGE_APP" APP_FRONTEND_IMAGE="$IMAGE_FRONTEND" \
  docker compose ${PROFILE_ARGS} -f "$COMPOSE" --env-file "$ENV_FILE" pull

echo "[deploy] docker pull worker image($TAG)"
docker pull "$IMAGE_WORKER"
docker image inspect "$IMAGE_WORKER" >/dev/null

echo "[deploy] docker compose up -d $TAG$([ -n "$PROFILE_ARGS" ] && echo ' + edge')"
APP_IMAGE="$IMAGE_APP" APP_FRONTEND_IMAGE="$IMAGE_FRONTEND" \
  docker compose ${PROFILE_ARGS} -f "$COMPOSE" --env-file "$ENV_FILE" up -d

echo "[deploy] 等就绪..."
READY=0
# 首次部署(无 last-good)Flyway 全量迁移慢,窗口加倍;日常发版 40×3s
MAX_TRIES=40
[ -z "$PREV_TAG" ] && MAX_TRIES=80
for i in $(seq 1 "$MAX_TRIES"); do
  # 验 app readiness + frontend SPA + MCP 反代。MCP 无 PAT 应由后端返回 401/403，而非 SPA。
  MCP_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' -X POST http://localhost:8081/mcp || true)"
  if curl -fsS http://localhost:8080/actuator/health/readiness >/dev/null 2>&1 \
     && curl -fsS http://localhost:8081/ >/dev/null 2>&1 \
     && [[ "$MCP_STATUS" == "401" || "$MCP_STATUS" == "403" ]]; then
    READY=1; break
  fi
  sleep 3
done

if [ "$READY" = 1 ]; then
  echo "[deploy] 就绪 ✓ ($TAG)"
  echo "$TAG" > "$LAST_GOOD"
  exit 0
fi

echo "[deploy] ✗ 健康超时($TAG),看 docker logs kwikquant-app" >&2
echo "[deploy] 数据库迁移可能已提交，禁止自动回滚到 $PREV_TAG；请按发布恢复点恢复数据库后再回退应用" >&2
exit 1
