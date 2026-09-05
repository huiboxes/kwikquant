import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { BrandMark } from '@/components/BrandMark'
import { docUrl } from '@/lib/docs'
import { ArrowRight } from 'lucide-react'

/**
 * LandingPage — 公开营销首页(未登录访问 / 时显)。开发者专版,产品叙事前置。
 *
 * 视觉走冷白节奏:灰画布段与纯白段交替,白卡无边界浮在灰段上,
 * 标题粗黑无衬线,装饰一律中性色,品牌橙只出现在主 CTA 与文档内联链接。
 *
 * 结构:Nav → Hero → 量化旅程 → 接入方式 → 能力目录 → 快速上手 → 场景演示 → AI Ready → Footer。
 */

// ------------------------------------------------------------
// 数据
// ------------------------------------------------------------
const STATS = [
  { value: 'OKX', label: '真实行情已验证' },
  { value: '免费', label: '模拟盘撮合' },
  { value: '23', label: 'MCP 工具' },
  { value: 'REST+WS', label: '任意语言直连' },
] as const

/** 量化旅程：写策略 → 回测 → 模拟 → 风控 → 实盘(产品主线)。 */
const JOURNEY = [
  {
    step: '01',
    title: '写策略',
    desc: 'Python on_bar 回调，均线/突破/网格模板起步，版本化管理。',
  },
  {
    step: '02',
    title: '回测验证',
    desc: '历史 K 线回放，产出收益/回撤/胜率指标，多版本对比。',
  },
  {
    step: '03',
    title: '模拟盘',
    desc: '真实行情 + 本地撮合，虚拟资金跑通逻辑，不花一分钱。',
  },
  {
    step: '04',
    title: '风控闸门',
    desc: '最大下单额 / 日亏损 / 频率限制，紧急停止立即生效，拒绝一切新下单。',
  },
  {
    step: '05',
    title: '实盘上线',
    desc: 'OKX 实盘，下单/平仓/启停均二次确认，审计留痕。',
  },
] as const

/** 接入方式四卡(CLI / Skill / MCP / REST)。 */
const INTEGRATIONS = [
  {
    name: 'MCP Server',
    tag: 'AI 客户端首选',
    desc: '一行 claude mcp add 接入，23 工具动态发现，PAT 鉴权，写操作二次确认。',
    // \\\n = 反斜杠 + 真换行(shell 续行),写 \\n 会渲染成字面 \n,复制进终端是坏命令;
    // 占位符与 SettingsPage INTEGRATION_RECIPES 保持一致
    snippet: 'claude mcp add --transport http kwikquant <ORIGIN>/mcp \\\n  --header "Authorization: Bearer <YOUR_PAT>"',
    doc: 'docs/mcp-setup.md',
    docLabel: 'MCP 接入',
  },
  {
    name: 'CLI',
    tag: '终端 / 脚本',
    desc: '行情/账户/订单/策略/回测全覆盖，--format json 可管道 jq，SSH 无头友好。',
    snippet: 'kwikquant auth login trader && kwikquant quote BTC/USDT',
    doc: 'docs/cli-reference.md',
    docLabel: 'CLI 命令参考',
  },
  {
    name: 'AI Skill',
    tag: '预打包工具集',
    desc: '5 个 Skill 覆盖行情/账户/策略/回测/交易，复制安装指南发给任意 AI 即可。',
    snippet: '把 skills/install.md 发给你的 AI 客户端',
    doc: 'skills/README.md',
    docLabel: 'Skill 目录',
  },
  {
    name: 'REST + WebSocket',
    tag: '任意语言',
    desc: '统一信封响应，金额字符串传输避免浮点误差，WS 实时推送行情/成交/持仓。',
    snippet: 'GET /api/v1/market/ticker/OKX/SPOT/BTC-USDT',
    doc: 'docs/api-reference.md',
    docLabel: 'REST API 参考',
  },
] as const

/** 能力目录：工具矩阵(开发者视角)。 */
const CAPABILITIES = [
  {
    name: '行情数据',
    count: 4,
    bullets: ['实时最新价', 'K 线多周期', '盘口深度', '资金费率'],
    desc: 'OKX 已验证，Binance / Bitget 接入中。现货与永续合约，行情实时推送。',
  },
  {
    name: '账户与组合',
    count: 4,
    bullets: ['交易所账户', '余额(按币种)', '组合总资产', '交易历史'],
    desc: '跨账户资产总览，API 密钥加密存储，不会完整展示。',
  },
  {
    name: '下单与持仓',
    count: 7,
    bullets: ['下单', '撤单', '持仓', '平仓', '资金费历史', '强平历史', '成交明细'],
    desc: '现货与永续合约，下单前经风控检查，高危操作二次确认。',
  },
  {
    name: '策略与回测',
    count: 5,
    bullets: ['回测执行', '结果对比', '模拟盘启动', '实盘启动', '策略管理'],
    desc: '回测 → 对比 → 模拟 → 实盘，渐进上线。',
  },
  {
    name: '风控',
    count: 3,
    bullets: ['规则查询', '规则设置', '紧急停止'],
    desc: '最大下单额 / 日亏损 / 频率限制，紧急停止全程留痕。',
  },
] as const

const STEPS = [
  {
    n: '01',
    title: '启动后端 + 签发 PAT',
    // tab 名与 SettingsPage 实际一致(「MCP 令牌」)，避免照文案找不到入口
    desc: './mvnw spring-boot:run；打开控制台，在 设置 → MCP 令牌 签发访问令牌(签发时可见，后续只存哈希)。',
  },
  {
    n: '02',
    title: '选一种方式接入',
    desc: 'Claude Code 一行 claude mcp add；或 cd cli && pnpm build 装 CLI；或直接用 REST/WS。',
  },
  {
    n: '03',
    title: '跑通第一次验证',
    desc: '说「查 OKX 现货 BTC/USDT 最新价」或运行 kwikquant quote BTC/USDT，拿到真实行情即接入成功。',
  },
] as const

/** 兼容客户端墙(单例)。单字母方块代替客户端 logo,避免第三方商标。 */
const CLIENTS = [
  { letter: 'C', name: 'Claude Code' },
  { letter: 'O', name: 'Codex' },
  { letter: 'C', name: 'Cursor' },
  { letter: 'G', name: 'Gemini' },
  { letter: 'Z', name: 'Zed' },
  { letter: '+', name: '任意 MCP 客户端' },
] as const

/** Footer 三分栏。外链 https:// 开新窗口，内站锚点 # 当前窗。 */
const FOOTER_COLS = [
  {
    title: '产品',
    links: [
      { label: '量化旅程', href: '#journey' },
      { label: '接入方式', href: '#integrations' },
      { label: '能力目录', href: '#capabilities' },
      { label: 'AI Ready', href: '#ai-ready' },
    ],
  },
  {
    title: '文档',
    links: [
      { label: '快速上手', href: docUrl('docs/quickstart.md') },
      { label: 'Cookbook', href: docUrl('docs/cookbook.md') },
      { label: 'CLI 命令参考', href: docUrl('docs/cli-reference.md') },
      { label: 'MCP 接入', href: docUrl('docs/mcp-setup.md') },
      { label: 'REST API 参考', href: docUrl('docs/api-reference.md') },
      { label: '变更日志', href: docUrl('docs/changelog.md') },
    ],
  },
  {
    title: '关于',
    links: [
      { label: 'KwikQuant', href: '/' },
      { label: 'GitHub', href: 'https://github.com/huiboxes/kwikquant' },
    ],
  },
] as const

// ------------------------------------------------------------
// 原子
// ------------------------------------------------------------
/** 段标题:粗黑大标题 + 灰色描述 + 可选文档内联链接(链接是页内唯一允许的橙色内联强调)。 */
function SectionTitle({
  title,
  desc,
  docHref,
  docLabel,
}: {
  title: string
  desc?: string
  docHref?: string
  docLabel?: string
}) {
  return (
    <div className="max-w-2xl">
      <h2 className="font-semibold text-h1 text-text-primary">{title}</h2>
      {desc && <p className="mt-sm text-body text-text-secondary">{desc}</p>}
      {docHref && (
        <a
          href={docHref}
          target="_blank"
          rel="noopener noreferrer"
          className="mt-md inline-flex items-center gap-xs text-body-sm text-accent hover:opacity-80"
        >
          {docLabel ?? '查看文档'} <ArrowRight className="size-3" aria-hidden />
        </a>
      )}
    </div>
  )
}

/** 白段上的统计行:数字粗黑,靠列间距分隔,不用卡片。 */
function StatRow() {
  return (
    <dl className="mt-xxl grid grid-cols-2 gap-x-lg gap-y-lg sm:grid-cols-4">
      {STATS.map((s) => (
        <div key={s.label} className="border-t border-onyx pt-sm">
          <dt className="font-mono-num text-metric font-semibold text-text-primary">{s.value}</dt>
          <dd className="mt-xxs text-caption text-text-muted">{s.label}</dd>
        </div>
      ))}
    </dl>
  )
}

export function LandingPage() {
  const publicOrigin = window.location.origin

  return (
    <div className="min-h-screen bg-surface-canvas font-body text-text-primary">
      {/* ────────────── Nav ────────────── */}
      <header className="sticky top-0 z-50 border-b border-border-soft bg-surface-card/90 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-lg">
          <Link to="/" className="flex items-center gap-sm" aria-label="KwikQuant 首页">
            <BrandMark className="h-7 w-auto" />
            <span className="text-h3 font-semibold">KwikQuant</span>
          </Link>
          <nav className="hidden items-center gap-lg sm:flex" aria-label="主导航">
            <a href="#journey" className="text-body-sm text-text-secondary hover:text-text-primary">量化旅程</a>
            <a href="#integrations" className="text-body-sm text-text-secondary hover:text-text-primary">接入方式</a>
            <a href="#capabilities" className="text-body-sm text-text-secondary hover:text-text-primary">能力</a>
            <a href="#install" className="text-body-sm text-text-secondary hover:text-text-primary">快速上手</a>
          </nav>
          <nav className="flex items-center gap-xs" aria-label="账户操作">
            <Button variant="ghost" size="sm" asChild>
              <Link to="/login">登录</Link>
            </Button>
            <Button size="sm" asChild>
              <Link to="/register">注册</Link>
            </Button>
          </nav>
        </div>
      </header>

      {/* ────────────── Hero(灰画布段:主张 + 统计) ────────────── */}
      <section className="mx-auto max-w-6xl px-lg py-section">
        <div className="max-w-3xl">
          <p className="text-label-caps text-text-muted">加密货币量化交易后端 · 可自托管</p>
          <h1 className="mt-md font-bold text-hero max-[640px]:text-display text-text-primary">
            把策略跑明白，再上实盘
          </h1>
          <p className="mt-lg max-w-2xl text-body text-text-secondary">
            KwikQuant 是一条从想法到实盘的量化后端：OKX 真实行情、本地撮合模拟盘、回测验证与风控闸门。
            先用虚拟资金把逻辑跑透，再决定是否上实盘。可通过 MCP / CLI / REST+WS 接入任意客户端。
          </p>
          <div className="mt-xl flex flex-wrap gap-sm">
            <Button size="lg" asChild>
              <Link to="/register">
                开始验证 <ArrowRight className="size-4" aria-hidden />
              </Link>
            </Button>
            <Button variant="outline" size="lg" asChild>
              <a href="#journey">看看怎么运作</a>
            </Button>
          </div>
        </div>
        <StatRow />
      </section>

      {/* ────────────── 量化旅程(白段:编号步骤平铺) ────────────── */}
      <section id="journey" className="bg-surface-card">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            title="回测验证 → 模拟盘 → 实盘，每一步都有据可依"
            desc="不直接拿真钱试错。先在历史行情上回测，再用真实行情的模拟盘验证，风控闸门全程兜底，最后才考虑实盘。"
          />
          <ol className="mt-xl grid grid-cols-1 gap-lg md:grid-cols-2 lg:grid-cols-5">
            {JOURNEY.map((j) => (
              <li key={j.step} className="border-t-2 border-onyx pt-md">
                <span className="font-mono-num text-caption text-text-muted">{j.step}</span>
                <h3 className="mt-xs text-h3 font-semibold text-text-primary">{j.title}</h3>
                <p className="mt-xs text-body-sm text-text-secondary">{j.desc}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      {/* ────────────── 接入方式(灰段:白卡 2×2) ────────────── */}
      <section id="integrations">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            title="MCP / CLI / Skill / REST，选你顺手的那一个"
            desc="同一套后端能力，四种接入面。AI 客户端走 MCP 或 Skill，脚本与终端走 CLI，任意语言直连 REST + WebSocket。"
          />
          <div className="mt-xl grid grid-cols-1 gap-lg md:grid-cols-2">
            {INTEGRATIONS.map((it) => (
              <div key={it.name} className="kq-card flex flex-col p-xl">
                <div className="flex items-baseline gap-sm">
                  <h3 className="text-h3 font-semibold text-text-primary">{it.name}</h3>
                  <span className="ml-auto text-caption-xs text-text-muted">{it.tag}</span>
                </div>
                <p className="mt-sm text-body-sm text-text-secondary">{it.desc}</p>
                <pre className="mt-md kq-code-block whitespace-pre-wrap break-all">
                  <code className="text-text-secondary">{it.snippet.replace('<ORIGIN>', publicOrigin)}</code>
                </pre>
                <a
                  href={docUrl(it.doc)}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="mt-md inline-flex items-center gap-xs text-body-sm text-accent hover:opacity-80"
                >
                  {it.docLabel} <ArrowRight className="size-3" aria-hidden />
                </a>
              </div>
            ))}
          </div>
          {/* 兼容客户端墙(单例) */}
          <div className="mt-xl">
            <p className="text-label-caps text-text-muted">兼容客户端</p>
            <div className="mt-sm flex flex-wrap items-center gap-md">
              {CLIENTS.map((c) => (
                <div key={c.name} className="flex items-center gap-xs">
                  <span className="flex size-8 items-center justify-center rounded-full bg-surface-card text-body-sm font-semibold text-text-secondary" aria-hidden>
                    {c.letter}
                  </span>
                  <span className="text-caption text-text-secondary">{c.name}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* ────────────── 能力目录(白段:平铺网格,不用卡片) ────────────── */}
      <section id="capabilities" className="bg-surface-card">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            title="23 个工具，5 个域，覆盖加密交易全流程"
            desc="行情 / 账户 / 下单 / 策略 / 风控。API 密钥加密存储，敏感字段在工具层脱敏。"
            docHref={docUrl('docs/cookbook.md')}
            docLabel="查看 Cookbook 任务式指南"
          />
          <div className="mt-xl grid grid-cols-1 gap-lg md:grid-cols-2 lg:grid-cols-3">
            {CAPABILITIES.map((c) => (
              <div key={c.name} className="border-t-2 border-onyx pt-md">
                <div className="flex items-baseline gap-sm">
                  <h3 className="text-h3 font-semibold text-text-primary">{c.name}</h3>
                  <span className="ml-auto font-mono-num text-caption text-text-muted">{c.count} 工具</span>
                </div>
                <p className="mt-sm text-body-sm text-text-secondary">{c.desc}</p>
                <ul className="mt-md flex flex-col gap-xs">
                  {c.bullets.map((b) => (
                    <li key={b} className="flex items-center gap-xs text-caption text-text-secondary">
                      <span className="size-1 rounded-full bg-text-muted" aria-hidden />
                      {b}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ────────────── 快速上手(灰段:三步白卡) ────────────── */}
      <section id="install">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            title="三步接入，几分钟跑通第一次验证"
            desc="启动后端 + 签 PAT → 选一种方式接入 → 拿到真实行情。"
            docHref={docUrl('docs/quickstart.md')}
            docLabel="查看快速上手"
          />
          <ol className="mt-xl grid grid-cols-1 gap-lg md:grid-cols-3">
            {STEPS.map((s) => (
              <li key={s.n} className="kq-card p-xl">
                <span className="font-mono-num text-stat font-semibold text-text-primary" aria-hidden>{s.n}</span>
                <h3 className="mt-xs text-h3 font-semibold text-text-primary">{s.title}</h3>
                <p className="mt-xs text-body-sm text-text-secondary">{s.desc}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      {/* ────────────── 场景演示(白段) ────────────── */}
      <section className="bg-surface-card">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            title="模拟盘自然语言下单，从说到成交"
            desc="在模拟盘上用自然语言下单，Agent 调用 MCP 工具完成查行情 → 风控 → 下单 → 成交回报。模拟盘不动真钱。"
            docHref={docUrl('docs/cookbook.md')}
            docLabel="查看更多场景"
          />
          <div className="mt-xl max-w-3xl rounded-xl bg-surface-card-2 p-xl">
            <div className="flex items-start gap-sm py-sm">
              <span className="kq-chip">用户</span>
              <p className="font-mono-num text-mono text-text-primary">在模拟盘上，okx 市价单买 0.001 BTC/USDT 现货</p>
            </div>
            <div className="flex items-start gap-sm py-sm">
              <span className="kq-chip">工具</span>
              <p className="font-mono-num text-mono text-text-secondary">→ submit_order(symbol=BTC/USDT, side=buy, type=market, amount=0.001, marketType=spot)</p>
            </div>
            <div className="flex items-start gap-sm py-sm">
              <span className="kq-chip">结果</span>
              <p className="font-mono-num text-mono text-text-secondary">已成交 · 数量 0.001 · 均价 64250.0 · 手续费 0.001285 USDT</p>
            </div>
          </div>
        </div>
      </section>

      {/* ────────────── AI Ready(灰段) ────────────── */}
      <section id="ai-ready">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            title="给 AI agent 一次读完的全量上下文"
            desc="遵循 llms.txt 社区规范(llmstxt.org)：llms.txt 站点大纲 + llms-full.txt 全量单页 markdown，AI agent 一次加载即可理解全部能力；OpenAPI 3 规范运行时可取。"
          />
          <div className="mt-xl flex flex-wrap gap-sm">
            <a href={docUrl('docs/llms.txt')} target="_blank" rel="noopener noreferrer" className="kq-chip">
              llms.txt(大纲)
            </a>
            <a href={docUrl('docs/llms-full.txt')} target="_blank" rel="noopener noreferrer" className="kq-chip">
              llms-full.txt(全量)
            </a>
            <a href={`${publicOrigin}/v3/api-docs`} target="_blank" rel="noopener noreferrer" className="kq-chip">
              OpenAPI /v3/api-docs
            </a>
            <a href={docUrl('docs/llm-integration.md')} target="_blank" rel="noopener noreferrer" className="kq-chip">
              AI 接入选型
            </a>
          </div>
          <p className="mt-lg max-w-2xl text-body-sm text-text-secondary">
            全量单页让模型一次拿到完整能力图谱，不必逐页翻阅(参考 llmstxt.org 社区实践)。
          </p>
        </div>
      </section>

      {/* ────────────── Footer(白段) ────────────── */}
      <footer className="border-t border-border-soft bg-surface-card">
        <div className="mx-auto max-w-6xl px-lg py-xl">
          <div className="grid grid-cols-2 gap-lg md:grid-cols-3">
            {FOOTER_COLS.map((col) => (
              <div key={col.title}>
                <p className="text-label-caps text-text-muted">{col.title}</p>
                <ul className="mt-sm flex flex-col gap-xs">
                  {col.links.map((l) => {
                    const external = l.href.startsWith('http')
                    return (
                      <li key={l.label}>
                        <a
                          href={l.href}
                          target={external ? '_blank' : undefined}
                          rel={external ? 'noopener noreferrer' : undefined}
                          className="text-body-sm text-text-secondary hover:text-text-primary"
                        >
                          {l.label}
                        </a>
                      </li>
                    )
                  })}
                </ul>
              </div>
            ))}
          </div>
          <div className="mt-xl flex flex-col gap-md border-t border-border-soft pt-lg">
            <div className="flex items-center gap-sm">
              <BrandMark className="h-6 w-auto" />
              <span className="text-body-sm font-semibold">KwikQuant</span>
              <span className="text-caption text-text-muted">© 2026</span>
            </div>
            <p className="text-caption text-text-muted">
              模拟盘免费验证，实盘交易涉及风险，请审慎决策。
            </p>
          </div>
        </div>
      </footer>
    </div>
  )
}
