#!/bin/bash
# =============================================================================
# post-receive.sh — 服务器 self-build 触发器(路径 B)
#
# 安装:
#   git init --bare /opt/kwikquant.git
#   cp /opt/kwikquant/docker/post-receive.sh /opt/kwikquant.git/hooks/post-receive
#   chmod +x /opt/kwikquant.git/hooks/post-receive
#
# 本地:
#   git remote add deploy user@server:/opt/kwikquant.git
#   git push deploy main      # 触发服务器 checkout + server-deploy.sh
#
# 与 GitHub Actions(路径 A)竞速:两路都调 server-deploy.sh,
# flock + 同 commit noop 保证谁快用谁。
# =============================================================================
set -euo pipefail

BARE=/opt/kwikquant.git
SRC=/opt/kwikquant/src

while read -r oldrev newrev ref; do
  # 只响应 main 分支
  if [ "$ref" != refs/heads/main ]; then
    echo "[post-receive] 跳过非 main 分支: $ref"
    continue
  fi

  echo "[post-receive] main -> $newrev,checkout 到 $SRC"
  mkdir -p "$SRC"
  git --work-tree="$SRC" --git-dir="$BARE" checkout -f main

  cd "$SRC"
  exec docker/server-deploy.sh "$newrev"
done
