#!/usr/bin/env bash
# api-contract-100 §1.5 验收硬门 3/4：openapi-typescript 生成 typed client + tsc 0 报错 + 抽样字段一致性
# 前置：./mvnw test -Dtest=OpenApiSpecTest -Pno-spotless（已生成 target/api-spec.json）
set -euo pipefail

SPEC=target/api-spec.json
if [ ! -f "$SPEC" ]; then
  echo "ERROR: $SPEC 不存在，先跑 ./mvnw test -Dtest=OpenApiSpecTest -Pno-spotless" >&2
  exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "→ openapi-typescript 生成 typed client..."
npx --yes openapi-typescript@latest "$SPEC" -o "$WORK/api-gen.ts"

echo "→ tsc 严格模式校验 0 报错..."
cat > "$WORK/tsconfig.json" <<'EOF'
{"compilerOptions":{"strict":true,"noEmit":true,"skipLibCheck":true,"target":"ES2022","module":"ESNext","moduleResolution":"bundler"},"include":["api-gen.ts"]}
EOF
( cd "$WORK" && npx --yes -p typescript@5.9.2 -- tsc --noEmit )

echo "→ 抽样字段一致性..."
# OrderSubmitRequest.side: string + description 含枚举
grep -q -- 'side: string' "$WORK/api-gen.ts"
grep -qF -- 'BUY | SELL' "$WORK/api-gen.ts"
# TokenResponse.accessToken（Auth login 响应）
grep -q -- 'accessToken' "$WORK/api-gen.ts"
# StrategyStatus: Java enum 生成字面量联合（DRAFT|READY|RUNNING|PAUSED|STOPPED|ERROR）
grep -qF -- '"DRAFT" | "READY" | "RUNNING" | "PAUSED" | "STOPPED" | "ERROR"' "$WORK/api-gen.ts"
# OrderDetailDto.status: String 字段 + description 列举 6 态
grep -qF -- 'NEW | PARTIAL | FILLED | CANCELLED | REJECTED | EXPIRED' "$WORK/api-gen.ts"

echo "✅ frontend codegen check passed (typed client 0 error + sampled fields consistent)"
