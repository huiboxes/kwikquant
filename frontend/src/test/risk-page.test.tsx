import { describe, it, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { RiskPage } from '@/pages/RiskPage'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'

/**
 * RiskPage × AiRuleDialog(P1-2 自然语言风控)页面级集成测试。
 * MSW 全局 handlers:risk(policies/decisions/parse/apply)+ account + settings(ai/keys)+ strategies。
 * 默认 fixture:账户 1(BINANCE 模拟)已有 MAX_NOTIONAL(id=42);parse fixture 返
 * MAX_NOTIONAL 5000 + ORDER_FREQUENCY 3 → 冲突覆盖路径天然可测。
 */
async function renderPage() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  const user = userEvent.setup()
  const utils = render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <RiskPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...utils, user }
}

/** 打开"一句话建规则" dialog 并返回输入框。 */
async function openDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /一句话建规则/ }))
  return screen.findByText('用自然语言描述风控要求，AI 解析后由你确认保存')
}

/** 点"开始解析":keys 查询未返回时按钮 disabled，先等可用再点(防测试竞态)。 */
async function clickParse(user: ReturnType<typeof userEvent.setup>) {
  const btn = screen.getByRole('button', { name: /开始解析/ })
  await waitFor(() => expect(btn).toBeEnabled())
  await user.click(btn)
}

// 注:handlers 的 POLICIES 是模块级可变数组(apply handler 会 push),vitest 每文件独立模块实例；
// 本文件用例均只读或幂等(apply 断言走 server.use override 捕获，不污染默认 fixture)。
describe('RiskPage 一句话建规则(P1-2)', () => {
  it('头部入口 → 打开 dialog:描述输入框 + 开始解析按钮', async () => {
    const { user } = await renderPage()
    await openDialog(user)
    expect(
      screen.getByPlaceholderText(/单笔下单不超过 5000 USDT/),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /开始解析/ })).toBeInTheDocument()
    // 空输入 → 解析按钮 disabled
    expect(screen.getByRole('button', { name: /开始解析/ })).toBeDisabled()
  })

  it('输入描述 → 解析 → 预览:summary 复述 + 两条规则 + 冲突覆盖提示', async () => {
    const { user } = await renderPage()
    await openDialog(user)
    await user.type(
      screen.getByPlaceholderText(/单笔下单不超过 5000 USDT/),
      '单笔不超过5000，每分钟最多下3单',
    )
    await clickParse(user)
    // summary 复述(handlers PARSE_FIXTURE)
    expect(await screen.findByText(/单笔不超过 5000 USDT，每分钟最多下 3 单/)).toBeInTheDocument()
    // 两条规则预览(name + 阈值格式化；页面 RuleCard 也渲染同阈值，用 getAll)
    expect(screen.getByText('单笔上限')).toBeInTheDocument()
    expect(screen.getByText('频率上限')).toBeInTheDocument()
    expect(screen.getAllByText(/5,000/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('3/min').length).toBeGreaterThanOrEqual(1)
    // 默认账户 1 已有 MAX_NOTIONAL(id=42)→ 冲突覆盖提示
    expect(screen.getByText(/该账户已有「单笔限额」规则，保存将覆盖/)).toBeInTheDocument()
    // 默认全选 → 确认按钮带计数
    expect(screen.getByRole('button', { name: /确认保存（2 条）/ })).toBeInTheDocument()
  })

  it('取消勾选一条 → 确认计数减少', async () => {
    const { user } = await renderPage()
    await openDialog(user)
    await user.type(screen.getByPlaceholderText(/单笔下单不超过 5000 USDT/), '限频')
    await clickParse(user)
    await screen.findByText('频率上限')
    // 取消勾选"频率上限"(checkbox aria-label)
    await user.click(screen.getByRole('checkbox', { name: '频率上限 是否保存' }))
    expect(screen.getByRole('button', { name: /确认保存（1 条）/ })).toBeInTheDocument()
  })

  it('账户 1(两类规则均已存在)确认保存 → 全部带 policyId 覆盖', async () => {
    let applyBody: Record<string, unknown> | null = null
    server.use(
      http.post('/api/v1/risk/policies/apply', async ({ request }) => {
        applyBody = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(envelope([]))
      }),
    )
    const { user } = await renderPage()
    await openDialog(user)
    await user.type(screen.getByPlaceholderText(/单笔下单不超过 5000 USDT/), '两条规则')
    await clickParse(user)
    await screen.findByText('单笔上限')
    // 默认账户 1:MAX_NOTIONAL(id=42)+ ORDER_FREQUENCY(id=43)均已存在 → 覆盖语义
    await user.click(screen.getByRole('button', { name: /确认保存（2 条）/ }))

    await waitFor(() => expect(applyBody).not.toBeNull())
    expect(applyBody!.accountId).toBe(1)
    const rules = applyBody!.rules as Array<{ policyId?: number; ruleType: string }>
    expect(rules).toHaveLength(2)
    expect(rules.find((r) => r.ruleType === 'MAX_NOTIONAL')?.policyId).toBe(42)
    expect(rules.find((r) => r.ruleType === 'ORDER_FREQUENCY')?.policyId).toBe(43)
    // dialog 关闭(标题离场)
    await waitFor(() => {
      expect(screen.queryByText('用自然语言描述风控要求，AI 解析后由你确认保存')).not.toBeInTheDocument()
    })
  })

  it('切换到无规则的账户 2 确认保存 → 全部新建(无 policyId)', async () => {
    let applyBody: Record<string, unknown> | null = null
    server.use(
      http.post('/api/v1/risk/policies/apply', async ({ request }) => {
        applyBody = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(envelope([]))
      }),
    )
    const { user } = await renderPage()
    await openDialog(user)
    await user.type(screen.getByPlaceholderText(/单笔下单不超过 5000 USDT/), '两条规则')
    await clickParse(user)
    await screen.findByText('单笔上限')
    // 切账户 2(主账户，无任何已有规则)→ 冲突提示消失，落库走新建
    await user.click(screen.getByRole('combobox'))
    await user.click(await screen.findByText(/主账户/))
    await waitFor(() => {
      expect(screen.queryByText(/保存将覆盖/)).not.toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /确认保存（2 条）/ }))

    await waitFor(() => expect(applyBody).not.toBeNull())
    expect(applyBody!.accountId).toBe(2)
    const rules = applyBody!.rules as Array<{ policyId?: number }>
    expect(rules).toHaveLength(2)
    expect(rules.every((r) => r.policyId === undefined)).toBe(true)
  })

  it('解析失败(8004)→ 输入框下方内联错误，不离开输入阶段', async () => {
    server.use(
      http.post('/api/v1/ai/risk-policy/parse', () =>
        HttpResponse.json(envelope(null, 8004, '未能解析出风控规则，请调整描述后重试'), { status: 400 }),
      ),
    )
    const { user } = await renderPage()
    await openDialog(user)
    await user.type(screen.getByPlaceholderText(/单笔下单不超过 5000 USDT/), '今天天气不错')
    await clickParse(user)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('未能解析出风控规则，请调整描述后重试')
    // 仍在输入阶段(可修改重试)
    expect(screen.getByPlaceholderText(/单笔下单不超过 5000 USDT/)).toBeInTheDocument()
  })

  it('无 LLM key → BYO 引导卡(去配置)，不渲染输入框', async () => {
    server.use(
      http.get('/api/v1/ai/keys', () => HttpResponse.json(envelope([]))),
    )
    const { user } = await renderPage()
    await openDialog(user)
    expect(await screen.findByText(/AI 解析采用 BYO 模式/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '去配置' })).toBeInTheDocument()
    expect(screen.queryByPlaceholderText(/单笔下单不超过 5000 USDT/)).not.toBeInTheDocument()
  })
})
