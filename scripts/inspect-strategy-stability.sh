#!/usr/bin/env bash
# 稳定性测试灰盒观测脚本 v3
# v3 修两缺陷:① WHERE 加 created_at >= warmup_ts 只取本策略订单(orders 无 strategy_id)
#             ② 采样只 >> $LOG 不 tee stdout,避免 background output 把 | 行 split 致 awk 解析错
#
# 用法: ./scripts/inspect-strategy-stability.sh <ACCOUNT_ID> <STRATEGY_ID> [DURATION_SEC]
set -uo pipefail

ACCOUNT_ID="${1:?usage: $0 <ACCOUNT_ID> <STRATEGY_ID> [DURATION_SEC=3600]}"
STRATEGY_ID="${2:?需要 STRATEGY_ID 用于容器名 strategy-worker-{id}}"
DURATION="${3:-3600}"
SYMBOL_SLASH="XRP/USDT"
CONTAINER_NAME="strategy-worker-${STRATEGY_ID}"
WARMUP=60
INTERVAL=30

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PGPW=$(grep -E '^POSTGRES_PASSWORD=' "$REPO_ROOT/.env" 2>/dev/null | head -1 | cut -d= -f2-)
: "${PGPW:?需要 $REPO_ROOT/.env 的 POSTGRES_PASSWORD}"

psql_cmd() {
    docker exec -i -e PGPASSWORD="$PGPW" kwikquant-postgres psql -U kwikquant -d kwikquant "$@"
}

LOOPS=$(( DURATION / INTERVAL ))
SAMPLES_DIR=$(mktemp -d)
LOG="$SAMPLES_DIR/samples.txt"

echo "[inspect] strategy=$STRATEGY_ID account=$ACCOUNT_ID container=$CONTAINER_NAME"
echo "[inspect] duration=${DURATION}s interval=${INTERVAL}s loops=$LOOPS samples_dir=$SAMPLES_DIR"
echo "[inspect] warmup ${WARMUP}s(首根 bar 只缓存不触发)..."
sleep "$WARMUP"
# warmup 后记录起始 epoch,WHERE created_at >= 此值,只取本策略 warmup 后订单(orders 无 strategy_id)
SAMPLE_START_EPOCH=$(date +%s)
echo "[inspect] 开始采样(start_epoch=$SAMPLE_START_EPOCH,只统计此后的订单)"
echo "time|run|restart|errs|total|ok|fail|min_ts|max_ts|pos_qty|bal_free|bal_used|mem" > "$LOG"

for i in $(seq 1 "$LOOPS"); do
    ts=$(date +%H:%M:%S)
    running=$(docker inspect --format '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null || echo "N/A")
    restarts=$(docker inspect --format '{{.RestartCount}}' "$CONTAINER_NAME" 2>/dev/null || echo "N/A")
    errs=$(docker logs --tail 30 "$CONTAINER_NAME" 2>&1 | grep -ciE "error|exception|traceback|reconnect|disconnect" || true)
    errs=${errs:-0}
    oline=$(psql_cmd -t -A -F'|' -c "SELECT count(*), count(*) FILTER(WHERE status IN ('FILLED','PARTIALLY_FILLED')), count(*) FILTER(WHERE status IN ('REJECTED','ERROR','CANCELLED','EXPIRED')), COALESCE(min(extract(epoch from created_at)),-1), COALESCE(max(extract(epoch from created_at)),-1) FROM orders WHERE account_id=$ACCOUNT_ID AND created_at >= to_timestamp($SAMPLE_START_EPOCH)" 2>/dev/null || echo "N/A|N/A|N/A|N/A|N/A")
    IFS='|' read -r total ok fail min_ts max_ts <<< "$oline"
    pos=$(psql_cmd -t -A -c "SELECT qty FROM positions WHERE account_id=$ACCOUNT_ID AND symbol='$SYMBOL_SLASH' ORDER BY updated_at DESC LIMIT 1" 2>/dev/null || echo "N/A")
    bal=$(psql_cmd -t -A -F'|' -c "SELECT free,used FROM paper_balances WHERE account_id=$ACCOUNT_ID AND currency='USDT'" 2>/dev/null || echo "N/A|N/A")
    IFS='|' read -r bal_free bal_used <<< "$bal"
    mem=$(docker stats --no-stream --format '{{.MemUsage}}' "$CONTAINER_NAME" 2>/dev/null || echo "N/A")
    # v3: 只写文件,不 tee stdout(避免 background output split 行)
    echo "$ts|$running|$restarts|$errs|$total|$ok|$fail|$min_ts|$max_ts|$pos|$bal_free|$bal_used|$mem" >> "$LOG"
    sleep "$INTERVAL"
done

# --- 汇总报告 ---
echo ""
echo "==================== 稳定性测试汇总报告(v3)===================="
echo "strategy_id=$STRATEGY_ID  account_id=$ACCOUNT_ID  container=$CONTAINER_NAME  duration=${DURATION}s  start_epoch=$SAMPLE_START_EPOCH"
echo ""
echo "--- 容器 ---"
max_restart=$(awk -F'|' 'NR>1{print $3}' "$LOG" | grep -v '^N/A' | sort -n | tail -1)
echo "RestartCount 最大值: ${max_restart:-(全 N/A)}"
run_ok=$(awk -F'|' 'NR>1{if($2=="true") c++} END{print c+0}' "$LOG")
echo "容器 Running 样本: $run_ok / $LOOPS"
echo ""
echo "--- 订单(本策略 warmup 后)---"
psql_cmd -t -A -F'|' -c "SELECT count(*), count(*) FILTER(WHERE status IN ('FILLED','PARTIALLY_FILLED')), count(*) FILTER(WHERE status IN ('REJECTED','ERROR','CANCELLED','EXPIRED')) FROM orders WHERE account_id=$ACCOUNT_ID AND created_at >= to_timestamp($SAMPLE_START_EPOCH)" 2>/dev/null \
    | awk -F'|' '{printf "total=%s ok=%s fail=%s\n成交率=%.1f%%  失败率=%.1f%%\n", $1,$2,$3, ($1>0?($2/$1)*100:0), ($1>0?($3/$1)*100:0)}'
echo ""
echo "--- 订单时间戳间隔(相邻 created_at 差,秒)---"
psql_cmd -t -A -c "SELECT extract(epoch from created_at - lag(created_at) OVER (ORDER BY created_at)) FROM orders WHERE account_id=$ACCOUNT_ID AND created_at >= to_timestamp($SAMPLE_START_EPOCH) AND status IN ('FILLED','PARTIALLY_FILLED')" 2>/dev/null \
    | awk '{ if(NF){n++;sum+=$1; if($1<min||min=="")min=$1; if($1>max)max=$1} } END{ if(n) printf "样本=%d 均值=%.1f 最小=%.1f 最大=%.1f(期望 60s ±15s)\n", n, sum/n, min, max; else print "(无 FILLED 订单)"}'
echo ""
echo "--- 持仓(最终)---"
psql_cmd -t -A -F'|' -c "SELECT qty,updated_at FROM positions WHERE account_id=$ACCOUNT_ID AND symbol='$SYMBOL_SLASH' ORDER BY updated_at DESC LIMIT 1" 2>/dev/null \
    | awk -F'|' '{print "qty="$1" updated_at="$2"  (期望 0 或 1,累积=异步撮合 lag 误 BUY)"}' || echo "(无持仓记录)"
echo ""
echo "--- 余额(最终)---"
psql_cmd -t -A -F'|' -c "SELECT free,used,total FROM paper_balances WHERE account_id=$ACCOUNT_ID AND currency='USDT'" 2>/dev/null \
    | awk -F'|' '{print "USDT free="$1" used="$2" total="$3"  (期望平稳微降,互抵+fee)"}' || echo "(无余额记录)"
echo ""
echo "--- 异常日志累计(docker logs grep)---"
total_errs=$(awk -F'|' 'NR>1{sum+=$4} END{print sum+0}' "$LOG")
echo "stderr 异常行累计: $total_errs  (期望 0)"
echo ""
echo "--- 内存(首末对比)---"
first_mem=$(awk -F'|' 'NR==2{print $NF}' "$LOG")
last_mem=$(awk -F'|' 'END{print $NF}' "$LOG")
echo "首样本: $first_mem"
echo "末样本: $last_mem  (期望平稳,无单调增长)"
echo ""
echo "样本日志: $LOG"
echo "==============================================================="
