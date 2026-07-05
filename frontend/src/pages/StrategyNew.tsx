/**
 * StrategyNew 页占位 — 批 1a step 12 实现 inline 创建(零 modal)。
 */
export function StrategyNew() {
  return (
    <div className="mx-auto max-w-[1240px] px-xl py-2xl text-text-primary">
      <p className="text-label-caps text-text-muted uppercase">Strategy</p>
      <h1 className="mt-md font-display text-h1">新建策略</h1>
      <p className="mt-sm font-body text-body text-text-secondary">
        占位页,批 1a step 12 接入 inline 创建表单(name/symbol/timeframe → POST /strategies)。
      </p>
    </div>
  )
}
