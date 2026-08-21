import type { Command } from 'commander'
import { apiGet } from './client.js'
import { output, table } from './output.js'
import type { RiskPolicyDto, PageDtoRiskDecisionDto } from './types.js'
import { globalOpts, fmt, fail, resolveCreds } from './shared.js'

/** 风控域:policies / decisions。 */
export function registerRisk(program: Command): void {
  const risk = program.command('risk').description('风控规则与决策审计')

  // ============================================================
  // risk policies — 列表(accountId 可选,省略跨账户)
  // RiskPolicyDto: id/accountId/ruleType/name/params(Map)/enabled/createdAt/updatedAt
  // ============================================================
  globalOpts(
    risk
      .command('policies')
      .description('查风控规则')
      .option('-a, --account <id>', '账户 ID(省略查当前用户全部)'),
  ).action(async (opts: { account?: string; format?: string; baseUrl?: string }) => {
    try {
      const creds = resolveCreds(opts)
      const qs = opts.account ? `?accountId=${opts.account}` : ''
      const data = await apiGet<RiskPolicyDto[]>(creds, `/api/v1/risk/policies${qs}`)
      output(data, fmt(opts), (d) => {
        if (d.length === 0) return '(空)'
        return table(
          ['ID', '账户', '规则类型', '名称', '参数', '启用'],
          d.map((p) => [
            String(p.id ?? '-'),
            String(p.accountId ?? '-'),
            String(p.ruleType ?? '-'),
            String(p.name ?? '-'),
            p.params == null ? '-' : JSON.stringify(p.params),
            String(p.enabled ?? '-'),
          ]),
        )
      })
    } catch (e) {
      fail(e)
    }
  })

  // ============================================================
  // risk decisions — 审计决策列表(accountId/verdict/时间 可选,分页)
  // 后端 list 端点 params="!orderId"(不收 orderId);带 orderId 命中单条端点返单个对象(非分页)
  // RiskDecisionDto: id/orderId/accountId/verdict/ruleResults/requestId/createdAt
  // ============================================================
  globalOpts(
    risk
      .command('decisions')
      .description('查风控决策审计')
      .option('-a, --account <id>', '账户 ID(省略查全部)')
      .option('--verdict <v>', '按 verdict 过滤 APPROVED | REJECTED')
      .option('--start <iso>', 'created_at 下限 ISO-8601')
      .option('--end <iso>', 'created_at 上限 ISO-8601')
      .option('--page <n>', '页码', '1')
      .option('--page-size <n>', '每页条数', '50'),
  ).action(
    async (opts: {
      account?: string
      verdict?: string
      start?: string
      end?: string
      page: string
      pageSize: string
      format?: string
      baseUrl?: string
    }) => {
      try {
        const creds = resolveCreds(opts)
        const params = new URLSearchParams({ page: opts.page, pageSize: opts.pageSize })
        if (opts.account) params.set('accountId', opts.account)
        if (opts.verdict) params.set('verdict', opts.verdict.toUpperCase())
        if (opts.start) params.set('startTime', opts.start)
        if (opts.end) params.set('endTime', opts.end)
        const data = await apiGet<PageDtoRiskDecisionDto>(creds, `/api/v1/risk/decisions?${params}`)
        output(data, fmt(opts), (d) => {
          const list = d.content ?? []
          if (list.length === 0) return '(空)'
          return table(
            ['ID', '订单ID', '账户', '决策', '规则结果', '时间'],
            list.map((r) => [
              String(r.id ?? '-'),
              String(r.orderId ?? '-'),
              String(r.accountId ?? '-'),
              String(r.verdict ?? '-'),
              r.ruleResults == null ? '-' : JSON.stringify(r.ruleResults),
              String(r.createdAt ?? '-'),
            ]),
          )
        })
      } catch (e) {
        fail(e)
      }
    },
  )
}
