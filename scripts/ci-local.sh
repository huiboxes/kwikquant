#!/usr/bin/env bash
# =============================================================================
# ci-local.sh — 在本机完整复现 .github/workflows/ci.yml 的 Backend Build & Test。
#
# 与 ci.yml 的差异只有一处:GitHub ubuntu-latest runner 自带可用 Docker,而受限
# 沙箱(cgroup 只读等)起不了容器。本脚本先做一次真实容器探测:
#   - Docker 可用   → 与 ci.yml 完全一致(Testcontainers 容器路径)
#   - Docker 不可用 → 自动切换本机原生 PostgreSQL(scripts/setup-local-postgres.sh
#                     + KQ_TEST_DB_URL 外部库模式),测试内容与门禁完全相同
# 两条路径跑的都是同一个 `./mvnw clean verify`:编译 + 单测 + 集成测试 +
# JaCoCo 95% 行覆盖门禁 + Spotless + ArchUnit/Modularity。
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

MAVEN_CLI_OPTS="-B -ntp -Dstyle.color=always"

echo "==> 校验 JDK 与 Maven wrapper(对齐 ci.yml 'Verify wrapper integrity')"
test -x ./mvnw
./mvnw --version | head -1

docker_usable() {
    command -v docker >/dev/null 2>&1 || return 1
    docker info >/dev/null 2>&1 || return 1
    # daemon 存活不等于能起容器(沙箱常见):用已缓存的 hello-world 做真实探测
    timeout 60 docker run --rm hello-world >/dev/null 2>&1
}

if docker_usable; then
    echo "==> Docker 可用:$(docker info --format 'Docker {{.ServerVersion}} ({{.OperatingSystem}})' 2>/dev/null)"
else
    echo "==> Docker 不可用:切换本机原生 PostgreSQL(外部库模式)"
    scripts/setup-local-postgres.sh
    export KQ_TEST_DB_URL="${KQ_TEST_DB_URL:-jdbc:postgresql://127.0.0.1:5432/kwikquant_test}"
    echo "    KQ_TEST_DB_URL=${KQ_TEST_DB_URL}"
fi

echo "==> ./mvnw ${MAVEN_CLI_OPTS} clean verify(对齐 ci.yml 'Verify' 步骤)"
./mvnw ${MAVEN_CLI_OPTS} clean verify

echo "==> CI 等价流程通过 ✔"
