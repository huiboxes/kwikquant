/**
 * backtestError — 回测失败/无成交的文案映射。
 *
 * 后端 Worker 跑回测引擎,失败时通过 WS BacktestEvent.error 推回原因(ws-contract §3.6)。
 * 但 error 是后端开发者视角的英文断言文案(如 'trades must not be empty'),直接 toast 给用户
 * 看不懂、也无从行动。这里把已知 error 关键词映射成产品化文案 + 可行动建议。
 *
 * 语义区分:
 *  - "无成交"(trades empty)→ warning(黄):不是错误,是策略在该区间的正常结果,给行动建议
 *  - 真实异常(代码错/数据加载失败)→ error(红):透出真实原因,引导查日志
 *  - 后端没给原因 → 兜底 error
 *
 * 与方案 B 的关系:后端把 trades empty 从 FAILED 改成 COMPLETED + 空 report 后,
 * 这条 warning 映射会自然走不到(因为不再推 FAILED)。但映射表保留作兜底,
 * 后端仍可能推别的 error,需要可读化。
 */

export interface BacktestFailure {
  title: string
  description: string
  tone: 'error' | 'warning'
}

/** 已知 error 关键词 → 产品化文案。匹配优先级从上到下。 */
const KNOWN_PATTERNS: { match: RegExp; result: BacktestFailure }[] = [
  {
    // 后端 'trades must not be empty' / 'no trades' 等:策略跑通但 0 笔成交
    match: /trades?\s+(must\s+not\s+be\s+)?empty|no\s+trades?|empty\s+trades?/i,
    result: {
      title: '回测完成,未产生成交',
      description:
        '策略在所选区间内没有触发任何买卖信号。可尝试扩大区间、放宽过滤条件或更换交易对。',
      tone: 'warning',
    },
  },
]

/** 把后端 error 文案映射成用户可读的 toast 内容。未匹配则原样透出(真实异常原因)。 */
export function mapBacktestError(error: string | null | undefined): BacktestFailure {
  if (!error) {
    return {
      title: '回测失败',
      description: '后端未返回原因,请查 Worker 日志后重试',
      tone: 'error',
    }
  }
  for (const p of KNOWN_PATTERNS) {
    if (p.match.test(error)) return p.result
  }
  return {
    title: '回测失败',
    description: error,
    tone: 'error',
  }
}
