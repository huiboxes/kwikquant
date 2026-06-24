#!/usr/bin/env bash
# =============================================================================
# gen-secrets.sh — 生成加密实盘上线所需的三把密钥
#
# 一次性生成、安全保管、**绝不入 git / 日志 / 截图 / 聊天**。
# 运行后把输出存入你的密钥管理（k8s Secret / Vault / 1Password 等），
# 再以环境变量注入容器。本脚本不写任何文件，只打印到 stdout。
#
# 用法:
#   ./scripts/ops/gen-secrets.sh            # 生成三把全新密钥
#   ./scripts/ops/gen-secrets.sh --check    # 只自检环境(openssl)，不生成
#
# 三把密钥:
#   1) JWT_SECRET — JWT 签名密钥，≥32 字节
#   2) API_KEY_HMAC_SECRET — API Key HMAC 摘要密钥，≥32 字节
#   3) ENCRYPTION_KEY — AES-256-GCM 加密密钥，base64(32B)
# =============================================================================
set -euo pipefail

err() { printf '\033[31m%s\033[0m\n' "$*" >&2; }
ok()  { printf '\033[32m%s\033[0m\n' "$*" >&2; }
note(){ printf '\033[33m%s\033[0m\n' "$*" >&2; }

if ! command -v openssl >/dev/null 2>&1; then
  err "openssl 未安装。macOS 自带；Linux: apt-get install openssl / yum install openssl"
  exit 1
fi

gen() { openssl rand -base64 32; }

verify_b64_32() {
  local v="$1" n
  n=$(printf '%s' "$v" | base64 -d 2>/dev/null | wc -c | tr -d ' ')
  [ "$n" = "32" ]
}

if [ "${1:-}" = "--check" ]; then
  t=$(gen)
  if verify_b64_32 "$t"; then ok "环境自检通过: openssl 可用，产出 base64(32B) 正确。"; exit 0
  else err "openssl 产出异常，请检查安装。"; exit 1; fi
fi

JWT_SECRET=$(gen)
API_KEY_HMAC_SECRET=$(gen)
ENCRYPTION_KEY=$(gen)

for pair in "JWT_SECRET=$JWT_SECRET" "API_KEY_HMAC_SECRET=$API_KEY_HMAC_SECRET" \
            "ENCRYPTION_KEY=$ENCRYPTION_KEY"; do
  name="${pair%%=*}"; val="${pair#*=}"
  bytes=$(printf '%s' "$val" | wc -c | tr -d ' ')
  if [ "$bytes" -lt 32 ]; then err "[$name] 字符串字节数 $bytes < 32，生成异常，已中止。"; exit 1; fi
  if [ "$name" = "ENCRYPTION_KEY" ] && ! verify_b64_32 "$val"; then
    err "[$name] base64 解码 != 32 字节(AES-256 要求)，已中止。"; exit 1
  fi
done

ok "三把密钥已生成并通过格式自检。"

note "—— 下面是密钥明文，仅本次显示。复制到密钥管理后请清屏(clear)。切勿入库/日志 ——"
cat <<EOF

# ===== KwikQuant 生产密钥 (生成于本机，注入为环境变量) =====
JWT_SECRET=$JWT_SECRET
API_KEY_HMAC_SECRET=$API_KEY_HMAC_SECRET
ENCRYPTION_KEY=$ENCRYPTION_KEY
# ============================================================
EOF

note "提醒:"
note "  • ENCRYPTION_KEY 一旦用于加密交易所凭证，轮转它=作废全部已存凭证(需重新录入)。"
note "  • JWT/HMAC 缺失→应用启动 fail-fast。"