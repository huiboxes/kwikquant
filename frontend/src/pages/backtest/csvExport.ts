import { toDecimal } from '@/lib/money'
import { effectLabel } from './positionEffect'
import type { BacktestReportDetailDto } from '@/api/backtest'

const BOM = '﻿'
const MAX_CURVE_POINTS = 1000

/** 策略名 sanitize 成文件名安全(去 \ / : * ? " < > | + 空格，替 -)。 */
export function sanitizeFileName(name: string): string {
  return name
    .replace(/[\\/:*?"<>|]+/g, '-')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
}

/** CSV 注入防御：单元格值以 = + - @ 开头加 ' 前缀转义(防 Excel formula injection)。接 unknown 兼容 number/string。 */
function escapeCell(v: unknown): string {
  const s = v == null ? '' : String(v)
  if (s && /^[=+\-@]/.test(s)) return `'${s}`
  return s
}

function fmtPct(v: number | null | undefined): string {
  if (v == null) return ''
  return `${toDecimal(v).times(100).toFixed(2)}%`
}

function fmtNum(v: number | null | undefined, dp = 2): string {
  if (v == null) return ''
  return toDecimal(v).toFixed(dp)
}

/**
 * 拼装回测 CSV(三段，空行分隔):指标区 + 权益曲线采样 + 交易明细。
 * - 金额用 decimal.js 格式化(禁 parseFloat/Number 金额运算)
 * - CSV 注入防御(= + - @ 前缀加 ')
 * - BOM 前缀防 Excel 中文乱码
 * - equityCurve 采样到 <=1000 点(对方 Excel 画图足够还原曲线，全量太大)
 */
export function buildBacktestCsv(detail: BacktestReportDetailDto, strategyName: string): string {
  const m = detail.metrics
  const perp = detail.marketType === 'PERP'
  const lines: string[] = []
  // 指标区
  lines.push('指标,值')
  lines.push('策略,' + escapeCell(strategyName))
  lines.push('交易对,' + escapeCell(detail.symbol))
  if (perp) {
    // PERP 报告必须携带近似模型声明(perp-backtest-spec §4.2),导出物脱离 UI 也不丢口径
    lines.push('市场类型,PERP')
    lines.push(
      '强平模型,' +
        escapeCell(
          detail.liquidationModel === 'BAR_EXTREME_APPROX'
            ? 'bar 极值近似(BAR_EXTREME_APPROX)'
            : (detail.liquidationModel ?? ''),
        ),
    )
    // 累计资金费汇总(与 UI 第 8 格同源:equity 末点 fundingCum,正=收取/负=支出)
    const lastFunding = (detail.equityCurve ?? []).at(-1)?.fundingCum
    if (lastFunding != null) {
      lines.push('累计资金费,' + escapeCell(lastFunding))
    }
  }
  lines.push('周期,' + escapeCell(detail.timeframe))
  lines.push('区间,' + escapeCell(`${detail.periodStart ?? ''} ~ ${detail.periodEnd ?? ''}`))
  lines.push('总收益率,' + escapeCell(fmtPct(m?.totalReturn)))
  lines.push('夏普比率,' + escapeCell(fmtNum(m?.sharpeRatio)))
  lines.push('最大回撤,' + escapeCell(fmtPct(m?.maxDrawdown)))
  lines.push('胜率,' + escapeCell(fmtPct(m?.winRate)))
  lines.push('盈亏比,' + escapeCell(fmtNum(m?.profitFactor)))
  lines.push('交易数,' + escapeCell(m?.totalTrades))
  lines.push('平均持仓(秒),' + escapeCell(m?.avgTradeDurationSeconds))
  lines.push('')
  // 权益曲线采样(PERP 带保证金占用/累计资金费扩展列;表头分隔符统一半角逗号,
  // 全角"，"在 Excel 里表头挤成 1 格与数据列错位)
  lines.push(perp ? '时间,权益,保证金占用,累计资金费' : '时间,权益')
  const curve = detail.equityCurve ?? []
  const step = Math.max(1, Math.ceil(curve.length / MAX_CURVE_POINTS))
  for (let i = 0; i < curve.length; i += step) {
    const p = curve[i]
    lines.push(
      perp
        ? `${escapeCell(p.time)},${escapeCell(p.equity)},${escapeCell(p.marginUsed)},${escapeCell(p.fundingCum)}`
        : `${escapeCell(p.time)},${escapeCell(p.equity)}`,
    )
  }
  lines.push('')
  // 交易明细(PERP 加持仓意图中文列与强平标记;side 是派生量,导出保留原值供对账)
  lines.push(
    perp
      ? '时间,方向,持仓意图,价格,数量(币),手续费,盈亏,强平'
      : '时间,方向,价格,数量,手续费,盈亏,权益',
  )
  for (const t of detail.trades ?? []) {
    const base = [escapeCell(t.time), escapeCell(t.side)]
    if (perp) base.push(escapeCell(effectLabel(t.positionEffect)))
    base.push(
      escapeCell(t.price),
      escapeCell(t.amount),
      escapeCell(t.fee),
      escapeCell(t.realizedPnl),
    )
    if (perp) {
      // PERP trades[].equity 恒 null(不含未实现/资金费,防与权益曲线背离误读)→ 不导出恒空列
      base.push(t.liquidation ? '是' : '')
    } else {
      base.push(escapeCell(t.equity))
    }
    lines.push(base.join(','))
  }
  return BOM + lines.join('\n')
}
