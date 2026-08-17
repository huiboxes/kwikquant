#!/usr/bin/env bash
# =============================================================================
# setup-local-postgres.sh — 无 Docker 环境下用本机原生 PostgreSQL 支撑开发/集成测试。
#
# 背景:受限沙箱(如 cgroup 只读的容器化开发机)无法运行 Docker/Testcontainers。
# 本机若已安装 PostgreSQL 16(Ubuntu: postgresql-16),可直接复用:
#   - 集成测试:AbstractIntegrationTest 检测到 KQ_TEST_DB_URL 后走"外部库"模式
#   - 本地开发:spring-boot:run 的 application-dev.yaml 默认就连 127.0.0.1:5432
#
# 本脚本幂等:启动 16/main cluster(已启动则跳过),创建角色与数据库(已存在则跳过):
#   - 角色 test / 库 kwikquant_test      → 集成测试用
#   - 角色 kwikquant / 库 kwikquant       → 本地开发(spring-boot:run)用
# =============================================================================
set -euo pipefail

PG_VERSION="${PG_VERSION:-16}"
CLUSTER="${PG_CLUSTER:-main}"

if ! command -v pg_ctlcluster >/dev/null 2>&1; then
    echo "错误:未找到 pg_ctlcluster。请先安装 PostgreSQL ${PG_VERSION}(Ubuntu: sudo apt-get install postgresql-${PG_VERSION})。" >&2
    exit 1
fi

# 1. 确保 cluster 在线
if pg_lsclusters | awk -v v="$PG_VERSION" -v c="$CLUSTER" '$1==v && $2==c {print $4}' | grep -q online; then
    echo "✓ PostgreSQL ${PG_VERSION}/${CLUSTER} 已在线"
else
    echo "→ 启动 PostgreSQL ${PG_VERSION}/${CLUSTER} ..."
    sudo pg_ctlcluster "$PG_VERSION" "$CLUSTER" start
fi

# 2. 幂等创建角色与数据库
sudo -u postgres psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'test') THEN
        CREATE ROLE test LOGIN PASSWORD 'test';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kwikquant') THEN
        CREATE ROLE kwikquant LOGIN PASSWORD 'kwikquant';
    END IF;
END
$$;
SELECT 'CREATE DATABASE kwikquant_test OWNER test'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kwikquant_test')\gexec
SELECT 'CREATE DATABASE kwikquant OWNER kwikquant'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kwikquant')\gexec
SQL
echo "✓ 角色 test/kwikquant 与数据库 kwikquant_test/kwikquant 就绪"

# 3. 冒烟验证:以 test 角色走 TCP + 密码认证(与 JDBC 连接方式一致)
PGPASSWORD=test psql -h 127.0.0.1 -p 5432 -U test -d kwikquant_test -tAc 'SELECT 1' >/dev/null
echo "✓ TCP 密码认证连接验证通过"

cat <<'EOF'

集成测试(无 Docker):
  export KQ_TEST_DB_URL='jdbc:postgresql://127.0.0.1:5432/kwikquant_test'
  ./mvnw test -Pno-spotless            # 可选:KQ_TEST_DB_USERNAME / KQ_TEST_DB_PASSWORD(默认 test/test)
  scripts/ci-local.sh                  # 或直接跑本机 CI 等价流程(自动探测 Docker)

本地开发(spring-boot:run):.env 中设置
  POSTGRES_PASSWORD=kwikquant          # application-dev.yaml 默认用户 kwikquant、空密码需覆盖
EOF
