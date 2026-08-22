#!/usr/bin/env bash
# =============================================================================
# setup-worker-env.sh — 一键搭建回测 worker 的 Python 环境(幂等)。
#
# 回测的 subprocess runner 消费 .venv/bin/python(application-dev.yaml 的
# kwikquant.worker.python-command,可用 KWIKQUANT_WORKER_PYTHON 覆盖)。
# 后端启动自检(BacktestWorkerHealthChecker)发现环境缺失时会自动做同样的事,
# 本脚本用于:手工预热(不等首次启动的 1-3 分钟)、自动搭建失败后排错、离线环境准备。
# 设置了 KWIKQUANT_WORKER_PYTHON(路径形态)时,脚本搭建它指向的环境(与后端消费
# 同一份),否则默认仓库根 .venv。
#
# 做法:创建虚拟环境(python3 -m venv) → 安装 requirements-worker.txt
#       → 验证与后端自检同一导入面(本地包 + 三方运行时依赖)。
# 安全护栏与后端自动搭建(WorkerEnvironmentProvisioner)一致:已存在的目标目录
# 必须是虚拟环境才清除/装依赖,不在系统目录下新建虚拟环境。
# 依赖网络访问 PyPI;受限网络先 export PIP_INDEX_URL=<镜像源>,例如:
#   export PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
# =============================================================================
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -n "${KWIKQUANT_WORKER_PYTHON:-}" && "${KWIKQUANT_WORKER_PYTHON}" == */* ]]; then
    # 与后端同口径:从解释器路径反推 venv 根(上两级)
    PYTHON="${KWIKQUANT_WORKER_PYTHON}"
    VENV_DIR="$(dirname "$(dirname "$PYTHON")")"
else
    VENV_DIR="${PROJECT_ROOT}/.venv"
    PYTHON="${VENV_DIR}/bin/python"
    if [[ -n "${KWIKQUANT_WORKER_PYTHON:-}" ]]; then
        # 裸命令名(如 python3)反推不出所属环境,脚本只能搭默认 .venv,提醒改成路径形态
        echo "注意:KWIKQUANT_WORKER_PYTHON 当前是裸命令名(${KWIKQUANT_WORKER_PYTHON}),后端消费的不是本脚本搭建的环境。" >&2
        echo "  搭建完成后请 export KWIKQUANT_WORKER_PYTHON=${VENV_DIR}/bin/python 再重启后端。" >&2
    fi
fi

# 与后端自检(BacktestWorkerHealthChecker.PROBE_IMPORTS)同一导入面,改动需同步
PROBE_IMPORTS='import kwikquant_worker, httpx, numpy, pandas, requests, websockets'

cd "$PROJECT_ROOT"

# 安全护栏(与后端自动搭建同款):
# 已存在但不是虚拟环境 → 拒绝清除/装依赖,防 --clear 误清、防往系统解释器里装包
if [[ -e "$VENV_DIR" && ! -f "$VENV_DIR/pyvenv.cfg" ]]; then
    echo "错误:目录 $VENV_DIR 已存在但不是 Python 虚拟环境(缺 pyvenv.cfg),出于安全未清除也未安装依赖。" >&2
    echo "  若该目录是误建/遗留的,手工删除或移走后重跑本脚本即可;" >&2
    echo "  若使用 conda 等其他环境,可手工执行: $PYTHON -m pip install -r requirements-worker.txt" >&2
    exit 1
fi
# 目标不存在时,不允许落在系统目录下新建
if [[ ! -e "$VENV_DIR" ]]; then
    VENV_PARENT="$(cd "$(dirname "$VENV_DIR")" 2>/dev/null && pwd || dirname "$VENV_DIR")"
    case "$VENV_PARENT" in
        /usr|/usr/*|/bin|/bin/*|/sbin|/sbin/*|/lib|/lib/*|/lib64|/lib64/*|/etc|/etc/*|/var|/var/*|/boot|/boot/*|/dev|/dev/*|/proc|/proc/*|/sys|/sys/*|/snap|/snap/*)
            echo "错误:拒绝在系统目录 $VENV_PARENT 下创建虚拟环境,请改用 <项目目录>/.venv。" >&2
            exit 1
            ;;
    esac
fi

# 1. 解释器:缺失/不可执行则创建 venv
if [[ -x "$PYTHON" ]] && "$PYTHON" --version >/dev/null 2>&1; then
    echo "✓ 复用已有 venv: $PYTHON ($("$PYTHON" --version 2>&1))"
else
    if ! command -v python3 >/dev/null 2>&1; then
        echo "错误:未找到 python3。请先安装 Python ≥3.11(Debian/Ubuntu: sudo apt install python3 python3-venv)。" >&2
        exit 1
    fi
    if ! python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)'; then
        echo "错误:系统 python3 版本过低($(python3 --version 2>&1)),需要 ≥3.11。" >&2
        exit 1
    fi
    if ! python3 -c 'import ensurepip' >/dev/null 2>&1; then
        echo "错误:python3 缺少 ensurepip/venv 模块(Debian/Ubuntu: sudo apt install python3-venv)。" >&2
        exit 1
    fi
    echo "→ 创建 venv: $VENV_DIR"
    python3 -m venv --clear "$VENV_DIR"
fi

# 2. 安装 worker 依赖(幂等;已满足则秒过)
echo "→ 安装 requirements-worker.txt(pip 镜像可提前 export PIP_INDEX_URL)"
"$PYTHON" -m pip install --quiet -r requirements-worker.txt

# 3. 验证:仓库根在 sys.path,本地包与三方依赖一并验
if "$PYTHON" -c "$PROBE_IMPORTS" >/dev/null 2>&1; then
    echo "✓ worker 环境就绪: $PYTHON"
    echo "  后端重启后启动自检会复用该环境;换 venv 位置时 export KWIKQUANT_WORKER_PYTHON=<venv>/bin/python 再重启。"
else
    echo "错误:依赖已安装但导入验证失败,请手工运行: $PYTHON -c '$PROBE_IMPORTS'" >&2
    exit 1
fi
