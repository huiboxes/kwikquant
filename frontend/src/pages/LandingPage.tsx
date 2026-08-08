import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { BrandMark } from '@/components/BrandMark'
import {
  ArrowRight,
  ArrowRightLeft,
  CandlestickChart,
  CheckCircle2,
  FlaskConical,
  Layers,
  ShieldCheck,
  Wallet,
  Workflow,
} from 'lucide-react'

/**
 * LandingPage — 公开营销首页(未登录访问 / 时显)。
 *
 * 像素级复刻长桥 open.longbridge.com/zh-CN 的 9 区块结构,适配加密域:
 * Nav → Hero(统计) → CLI(终端演示) → AI Skill(客户端墙+对话) → 托管 MCP(接入验证) →
 * REST+WS 直连 → 能力目录(数字+bullet) → Get started(01/02/03) → 场景演示 → AI Ready → Footer(四分栏)。
 * 视觉严格走 DESIGN.md token(primary 暖橙 + Cormorant Garamond + 暖白画布),零硬编码颜色/圆角/字号。
 * 客户端 logo 用单字母方块仿长桥(避免第三方商标版权),终端 traffic-light 用 up/down/warning 语义 token 近似。
 *
 * 每个 section 的 SectionTitle 传 docHref 跳转对应文档(GitHub markdown,本地阶段;公网分发后改 docs 站)。
 */

/** 文档外链基址:本地阶段指向 GitHub 仓库 markdown;公网分发后改 https://kwikquant.dev/docs/<slug>。 */
const DOC_BASE = 'https://github.com/huiboxes/kwikquant/blob/main'
const docUrl = (p: string) => `${DOC_BASE}/${p}`

// ------------------------------------------------------------
// 数据
// ------------------------------------------------------------
const STATS = [
  { value: '3', label: '交易所' },
  { value: '23', label: 'MCP 工具' },
  { value: '5', label: 'Skill 包' },
  { value: '$0', label: '模拟盘接入费' },
] as const

/** CLI 区块左侧特性 bullet。 */
const CLI_FEATURES = [
  { title: '查询命令全覆盖', desc: '行情 / 账户 / 组合 / 订单 / 持仓 / 策略 / 回测 / 风控——一个终端全触达。' },
  { title: '--format json', desc: '可直接管道传输给 jq / awk,或喂给任意 AI Agent 的工具通道。' },
  { title: '多周期 K 线', desc: '1 分 / 5 分 / 15 分 / 1 小时 / 日线——一个 --period 参数搞定。' },
  { title: '组合盈亏下钻', desc: '持仓明细 + 总资产 USDT + 权益曲线,配置占比一目了然。' },
  { title: 'SSH 无头友好', desc: 'JWT 本地 0600 存储,无浏览器环境与 Docker 容器内可跑。' },
] as const

const CLI_STATS = [
  { value: '20+', label: '命令' },
  { value: '2', label: '输出格式' },
  { value: '5', label: '能力域' },
  { value: '实时', label: 'REST 直连' },
] as const

/** AI Skill 区块客户端墙(单字母方块仿长桥,避免第三方商标)。 */
const SKILL_CLIENTS = [
  { letter: 'C', name: 'Claude Code' },
  { letter: 'O', name: 'Codex' },
  { letter: 'C', name: 'Cursor' },
  { letter: 'G', name: 'Gemini' },
  { letter: 'Z', name: 'Zed' },
  { letter: '+', name: '任意 MCP 客户端' },
] as const

/** 托管 MCP 区块客户端墙。 */
const MCP_CLIENTS = [
  { letter: 'C', name: 'Claude Code' },
  { letter: 'O', name: 'Codex' },
  { letter: 'C', name: 'Cursor' },
  { letter: 'G', name: 'Gemini CLI' },
  { letter: 'Z', name: 'Zed' },
  { letter: 'W', name: 'Warp' },
] as const

/** REST + WS 直连区块特性。 */
const SDK_FEATURES = [
  { title: '多交易所覆盖', desc: 'OKX · Binance · Bitget,SPOT 现货与永续合约 PERP。' },
  { title: '模拟盘免费', desc: '用真实行情数据模拟撮合,无需交易所账户即可验证策略。' },
  { title: '实时推送', desc: 'WebSocket 推送行情 / 成交 / 订单状态 / 持仓 delta,延迟 < 60 ms。' },
  { title: 'JWT + PAT 双轨', desc: 'REST 走 JWT, MCP 走 PAT,HMAC 哈希 + pepper fail-closed。' },
] as const

/** 能力目录:数字 + bullet 明细(对标长桥 30+/14+/8+...)。 */
const CAPABILITIES = [
  {
    icon: CandlestickChart,
    name: '行情数据',
    count: 4,
    bullets: ['实时最新价', 'K 线多周期', '盘口深度', '资金费率'],
    desc: 'OKX / Binance / Bitget,SPOT + PERP,WS 实时推送 + REST fallback。',
  },
  {
    icon: Wallet,
    name: '账户与组合',
    count: 4,
    bullets: ['交易所账户', '余额(按币种)', '组合总资产', '交易历史'],
    desc: '跨账户资产总览,apiKey 隔离不暴露给 Agent。',
  },
  {
    icon: ArrowRightLeft,
    name: '下单与持仓',
    count: 7,
    bullets: ['下单', '撤单', '持仓', '平仓', '资金费历史', '强平历史', '成交明细'],
    desc: 'SPOT + PERP,经风控网关,高危操作二次确认。',
  },
  {
    icon: FlaskConical,
    name: '策略与回测',
    count: 5,
    bullets: ['回测执行', '结果对比', '模拟盘启动', '实盘启动', '策略管理'],
    desc: '回测 → 对比 → 模拟 → 实盘,渐进上线,Worker 编排。',
  },
  {
    icon: ShieldCheck,
    name: '风控',
    count: 3,
    bullets: ['规则查询', '规则设置', '紧急停止'],
    desc: '最大下单额 / 日亏损 / 频率,紧急停止 fail-closed 审计。',
  },
] as const

const STEPS = [
  {
    n: '01',
    title: '启动后端 + 签发 PAT',
    desc: './mvnw spring-boot:run,MCP server 暴露在 http://localhost:8080/mcp;登录前端签发 PAT(明文仅一次)。',
  },
  {
    n: '02',
    title: '接入 AI 或装 CLI',
    desc: 'Claude Code 一行 claude mcp add 接入 MCP;或 cd cli && pnpm build 装 kwikquant CLI 直连 REST。',
  },
  {
    n: '03',
    title: '自然语言查行情',
    desc: '重启 Claude Code,说"列出我的交易所账户"或"查 okx 永续 BTC/USDT 最新价",应触发 MCP 工具。',
  },
] as const

/** Footer 四分栏。外链 https:// 开新窗口,内站锚点 # 当前窗。 */
const FOOTER_COLS = [
  {
    title: '产品',
    links: [
      { label: 'MCP Server', href: '#mcp' },
      { label: 'AI Skill', href: '#skill' },
      { label: 'CLI', href: '#cli' },
      { label: 'REST + WebSocket', href: '#sdk' },
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
      { label: '状态', href: '#' },
    ],
  },
  {
    title: '法律',
    links: [
      { label: '服务条款', href: '#' },
      { label: '隐私政策', href: '#' },
      { label: '免责声明', href: '#' },
    ],
  },
] as const

// ------------------------------------------------------------
// 原子:macOS 终端窗口(traffic-light 用 up/down/warning 语义 token 近似)
// ------------------------------------------------------------
function TerminalWindow({
  title,
  lines,
}: {
  title: string
  lines: { prompt?: string; text: string; tone?: 'cmd' | 'out' | 'ok' | 'muted' }[]
}) {
  const toneClass = {
    cmd: 'text-text-primary',
    out: 'text-text-secondary',
    ok: 'text-up',
    muted: 'text-text-muted',
  }
  return (
    <div className="overflow-hidden rounded-lg border border-border-soft bg-surface-card-2 shadow-card">
      <div className="flex items-center gap-xs border-b border-border-soft px-md py-xs">
        <span className="size-3 rounded-full bg-down" aria-hidden />
        <span className="size-3 rounded-full bg-warning" aria-hidden />
        <span className="size-3 rounded-full bg-up" aria-hidden />
        <span className="ml-xs font-mono text-caption text-text-muted">{title}</span>
      </div>
      <pre className="overflow-x-auto p-md font-mono text-mono leading-relaxed">
        <code>
          {lines.map((l, i) => (
            <div key={i} className={toneClass[l.tone ?? 'cmd']}>
              {l.prompt && <span className="text-accent">{l.prompt} </span>}
              {l.text}
            </div>
          ))}
        </code>
      </pre>
    </div>
  )
}

/** 客户端单字母方块墙(仿长桥,避免第三方商标)。 */
function ClientWall({ clients }: { clients: readonly { letter: string; name: string }[] }) {
  return (
    <div className="flex flex-wrap items-center gap-sm">
      {clients.map((c) => (
        <div key={c.name} className="flex items-center gap-xs">
          <span className="flex size-8 items-center justify-center rounded-md bg-accent font-display text-h3 text-on-accent" aria-hidden>
            {c.letter}
          </span>
          <span className="text-caption text-text-secondary">{c.name}</span>
        </div>
      ))}
    </div>
  )
}

/** 区块标题(统一视觉,可选文档跳转链接)。 */
function SectionTitle({
  kicker,
  title,
  desc,
  docHref,
  docLabel,
}: {
  kicker: string
  title: string
  desc?: string
  docHref?: string
  docLabel?: string
}) {
  return (
    <div className="max-w-2xl">
      <p className="text-label-caps text-accent">{kicker}</p>
      <h2 className="mt-xs font-display text-h1 text-text-primary">{title}</h2>
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

export function LandingPage() {
  return (
    <div className="min-h-screen bg-surface-canvas font-body text-text-primary">
      {/* ────────────── Nav ────────────── */}
      <header className="sticky top-0 z-50 border-b border-border-soft bg-surface-canvas/80 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-lg">
          <Link to="/" className="flex items-center gap-sm" aria-label="KwikQuant 首页">
            <BrandMark className="h-7 w-auto" />
            <span className="font-display text-h2">KwikQuant</span>
          </Link>
          <nav className="hidden items-center gap-lg sm:flex" aria-label="主导航">
            <a href="#cli" className="text-body-sm text-text-secondary hover:text-text-primary">CLI</a>
            <a href="#skill" className="text-body-sm text-text-secondary hover:text-text-primary">Skill</a>
            <a href="#mcp" className="text-body-sm text-text-secondary hover:text-text-primary">MCP</a>
            <a href="#capabilities" className="text-body-sm text-text-secondary hover:text-text-primary">能力</a>
            <a href="#ai-ready" className="text-body-sm text-text-secondary hover:text-text-primary">AI Ready</a>
            <a href="#install" className="text-body-sm text-text-secondary hover:text-text-primary">文档</a>
          </nav>
          <nav className="flex items-center gap-xs" aria-label="账户操作">
            <Button variant="ghost" size="sm" asChild>
              <Link to="/login">登录</Link>
            </Button>
            <Button size="sm" asChild>
              <Link to="/register">免费注册</Link>
            </Button>
          </nav>
        </div>
      </header>

      {/* ────────────── Hero ────────────── */}
      <section className="relative overflow-hidden border-b border-border-soft">
        <div className="absolute inset-0 kq-grid-bg opacity-40" aria-hidden />
        <div className="relative mx-auto max-w-6xl px-lg py-section">
          <div className="max-w-3xl">
            <span className="kq-chip kq-chip--accent">加密货币量化交易 · MCP SERVER</span>
            <h1 className="mt-lg font-display text-display text-text-primary">
              AI 直连真实行情
            </h1>
            <p className="mt-lg max-w-2xl text-body text-text-secondary">
              通过 MCP / Skill / CLI / REST+WS 一体接入 OKX / Binance / Bitget,SPOT 现货与永续合约 PERP
              (杠杆 / 保证金模式 / 资金费率 8h 结算 / 强平)。一套凭证覆盖 23 个工具,模拟盘免费验证,实盘高危操作二次确认。
            </p>
            <div className="mt-xl flex flex-wrap gap-sm">
              <Button size="lg" asChild>
                <Link to="/register">
                  开始使用 <ArrowRight className="size-4" aria-hidden />
                </Link>
              </Button>
              <Button variant="outline" size="lg" asChild>
                <a href={docUrl('docs/quickstart.md')} target="_blank" rel="noopener noreferrer">
                  快速上手
                </a>
              </Button>
            </div>
          </div>
          {/* 数据统计 */}
          <dl className="mt-xxl grid grid-cols-2 gap-lg sm:grid-cols-4">
            {STATS.map((s) => (
              <div key={s.label} className="kq-card p-lg">
                <dt className="font-display text-display text-accent">{s.value}</dt>
                <dd className="mt-xs text-caption text-text-muted">{s.label}</dd>
              </div>
            ))}
          </dl>
        </div>
      </section>

      {/* ────────────── CLI 区块(终端演示) ────────────── */}
      <section id="cli" className="border-b border-border-soft">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            kicker="KWIKQUANT CLI"
            title="AI 原生命令行,直连后端所有 REST"
            desc="覆盖行情 / 账户 / 组合 / 订单 / 持仓 / 策略 / 回测 / 风控,--format json 可管道 jq,SSH 无头环境与 Docker 友好。"
            docHref={docUrl('docs/cli-reference.md')}
            docLabel="查看 CLI 命令参考"
          />
          <div className="mt-xl grid gap-lg lg:grid-cols-2">
            {/* 左:特性 + 统计 */}
            <div className="flex flex-col gap-md">
              <ul className="flex flex-col gap-md">
                {CLI_FEATURES.map((f) => (
                  <li key={f.title} className="flex items-start gap-sm">
                    <CheckCircle2 className="mt-xs size-4 shrink-0 text-accent" aria-hidden />
                    <div>
                      <p className="font-display text-h3 text-text-primary">{f.title}</p>
                      <p className="mt-xs text-body-sm text-text-secondary">{f.desc}</p>
                    </div>
                  </li>
                ))}
              </ul>
              <dl className="mt-sm grid grid-cols-4 gap-sm rounded-lg border border-border-soft bg-surface-card-2 p-md">
                {CLI_STATS.map((s) => (
                  <div key={s.label}>
                    <dt className="font-display text-h2 text-accent">{s.value}</dt>
                    <dd className="text-caption text-text-muted">{s.label}</dd>
                  </div>
                ))}
              </dl>
            </div>
            {/* 右:macOS 终端窗口多行命令流 */}
            <TerminalWindow
              title="kwikquant — bash"
              lines={[
                { prompt: '$', text: 'cd cli && pnpm build && npm link -g', tone: 'cmd' },
                { prompt: '$', text: 'kwikquant auth login trader ****', tone: 'cmd' },
                { text: '✓ 已登录 trader,JWT 已存 ~/.kwikquant/credentials.json', tone: 'ok' },
                { prompt: '$', text: 'kwikquant quote BTC/USDT ETH/USDT', tone: 'cmd' },
                { text: '交易对     最新价    买一     卖一     24h量', tone: 'muted' },
                { text: '--------   ------   -----    -----    -----', tone: 'muted' },
                { text: 'BTC/USDT   64998.3   64998.3  64998.4  3239.45', tone: 'out' },
                { text: 'ETH/USDT   3128.5    3128.4   3128.5   18422.7', tone: 'out' },
                { prompt: '$', text: 'kwikquant portfolio --format json | jq \'.accounts[] | .totalUsdt\'', tone: 'cmd' },
                { text: '71921.92', tone: 'out' },
              ]}
            />
          </div>
        </div>
      </section>

      {/* ────────────── AI Skill 区块(客户端墙 + 对话演示) ────────────── */}
      <section id="skill" className="border-b border-border-soft bg-surface-card-2">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            kicker="AI SKILL · 预打包工具"
            title="为你的 AI 解锁加密市场洞察与智能交易"
            desc="5 个 Anthropic Agent Skill 按域分包,可被任意 MCP 客户端调用——筛标的、解读资金费、追踪强平、下单,全在自然语言对话中完成,无需切换应用。"
            docHref={docUrl('skills/README.md')}
            docLabel="查看 Skill 目录"
          />
          <div className="mt-xl grid gap-lg lg:grid-cols-2">
            {/* 左:安装代码块 + 客户端墙 */}
            <div className="flex flex-col gap-lg">
              <div className="kq-code-block">
                <p className="text-text-muted"># 复制发给任意 AI,它会引导你完成安装(公网分发后替换域名):</p>
                <p className="text-text-secondary">请按照以下指南安装 KwikQuant AI toolkit:</p>
                <p className="text-text-secondary">https://kwikquant.dev/skill/install.md</p>
                <p className="text-text-secondary">安装完成后,完成登录授权,查询 BTC/USDT 行情确认可用。</p>
                <p className="mt-sm text-text-muted">— 或通过包管理器(公网分发后可用)—</p>
                <p><span className="text-accent">$ </span><span className="text-text-primary">npx skills add kwikquant/skills -g</span></p>
                <p className="text-text-muted"># 本地阶段:复制 skills/ 目录到 ~/.claude/skills/</p>
              </div>
              <div>
                <p className="text-label-caps text-text-muted">兼容客户端</p>
                <div className="mt-sm">
                  <ClientWall clients={SKILL_CLIENTS} />
                </div>
              </div>
            </div>
            {/* 右:对话演示 */}
            <div className="kq-card flex flex-col gap-sm p-xl">
              <div className="flex items-center gap-sm border-b border-border-soft pb-sm">
                <span className="kq-status-dot kq-status-dot-live bg-up text-up" aria-hidden />
                <span className="text-body-sm text-text-secondary">Claude Code · skill: kwikquant connected</span>
              </div>
              <p className="text-body text-text-primary">
                查 okx 永续 BTC/USDT 最新价和当前资金费率,我该不该持有这个多仓?
              </p>
              <div className="flex flex-wrap items-center gap-xs">
                <span className="kq-chip kq-chip--info">used `get_ticker`</span>
                <span className="kq-chip kq-chip--info">used `get_funding_rate`</span>
              </div>
              <p className="text-body-sm text-text-secondary">
                BTC/USDT PERP 最新价 <span className="kq-mono-row text-text-primary">64998.3</span>,
                资金费率 <span className="kq-mono-row text-up">+0.012%/8h</span>(多头付费)。
                你的多仓均价 <span className="kq-mono-row text-text-primary">64250</span>,
                浮盈 <span className="kq-mono-row text-up">+1.16%</span>。资金费 8h 结算一次,持仓成本需关注,
                杠杆建议维持 ≤ 5x,跌破 63500 考虑减仓。
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* ────────────── 托管 MCP 区块(接入 + 验证) ────────────── */}
      <section id="mcp" className="border-b border-border-soft">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            kicker="托管 MCP"
            title="一行命令接入,PAT 鉴权动态发现"
            desc="Streamable HTTP + PAT 鉴权。Claude Code / Cursor 一行 claude mcp add 接入,23 工具动态发现,无需手动配置;高危操作 confirm 二次确认。"
            docHref={docUrl('docs/mcp-setup.md')}
            docLabel="查看 MCP 接入"
          />
          <div className="mt-xl grid gap-lg lg:grid-cols-2">
            <TerminalWindow
              title="claude — bash"
              lines={[
                { prompt: '$', text: 'claude mcp add --transport http kwikquant \\', tone: 'cmd' },
                { text: '  http://localhost:8080/mcp \\', tone: 'cmd' },
                { text: '  --header "Authorization: Bearer <YOUR_PAT>"', tone: 'cmd' },
                { prompt: '$', text: 'claude mcp list', tone: 'cmd' },
                { text: 'kwikquant ✓ ready 23 tools', tone: 'ok' },
              ]}
            />
            <div className="flex flex-col justify-center gap-lg">
              <div>
                <p className="text-label-caps text-text-muted">兼容客户端</p>
                <div className="mt-sm">
                  <ClientWall clients={MCP_CLIENTS} />
                </div>
              </div>
              <p className="text-body-sm text-text-secondary">
                首次工具调用触发 PAT 校验(HMAC + pepper fail-closed)。apiKey 等敏感字段在 MCP 工具层剥离,
                不暴露给 Agent;写操作(下单 / 平仓 / 实盘启动 / 紧急停止)须显式{' '}
                <code className="font-mono text-mono text-accent">confirm=true</code>。
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* ────────────── REST + WebSocket 直连区块(对标长桥 SDK) ────────────── */}
      <section id="sdk" className="border-b border-border-soft bg-surface-card-2">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            kicker="REST + WEBSOCKET"
            title="生产级 HTTP/WS 接口,任意语言可接"
            desc="无需 SDK 依赖,REST + WebSocket 直连后端,响应统一 ApiResponse 信封 {code, message, data},BigDecimal 金额序列化为 string。"
            docHref={docUrl('docs/api-reference.md')}
            docLabel="查看 REST API 参考"
          />
          <div className="mt-xl grid gap-lg lg:grid-cols-2">
            <div className="flex flex-col gap-md">
              <ul className="flex flex-col gap-md">
                {SDK_FEATURES.map((f) => (
                  <li key={f.title} className="flex items-start gap-sm">
                    <Layers className="mt-xs size-4 shrink-0 text-accent" aria-hidden />
                    <div>
                      <p className="font-display text-h3 text-text-primary">{f.title}</p>
                      <p className="mt-xs text-body-sm text-text-secondary">{f.desc}</p>
                    </div>
                  </li>
                ))}
              </ul>
            </div>
            <div className="flex flex-col gap-md">
              <div>
                <p className="text-label-caps text-text-muted">REST 查最新价</p>
                <pre className="mt-xs kq-code-block">
                  <code>
                    <span className="text-accent">$ </span><span className="text-text-primary">curl -H "Authorization: Bearer $JWT" \</span>{'\n'}
                    <span className="text-text-primary">  http://localhost:8080/api/v1/market/ticker/OKX/SPOT/BTC-USDT</span>
                  </code>
                </pre>
              </div>
              <div>
                <p className="text-label-caps text-text-muted">WS 订阅实时行情</p>
                <pre className="mt-xs kq-code-block">
                  <code>
                    <span className="text-info">const</span> ws = <span className="text-info">new</span> WebSocket(<span className="text-up">'ws://localhost:8080/ws'</span>){'\n'}
                    ws.send(JSON.stringify({'{'}{'\n'}
                    {'  '}destination: <span className="text-up">'/topic/ticker/OKX/SPOT/BTC-USDT'</span>{'\n'}
                    {'}'}))
                  </code>
                </pre>
                <a
                  href={docUrl('docs/ws-contract.md')}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="mt-xs inline-flex items-center gap-xs text-caption text-accent hover:opacity-80"
                >
                  查看 WebSocket 契约 <ArrowRight className="size-3" aria-hidden />
                </a>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ────────────── 能力目录(数字 + bullet) ────────────── */}
      <section id="capabilities" className="border-b border-border-soft">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            kicker="API CAPABILITIES"
            title="23 个工具,5 个域,覆盖加密交易全流程"
            desc="每个 Skill 都是一套打包工具集,按域分包,可被任意 MCP 客户端动态发现调用。"
            docHref={docUrl('docs/cookbook.md')}
            docLabel="查看 Cookbook 任务式指南"
          />
          <div className="mt-xl grid gap-lg md:grid-cols-2 lg:grid-cols-3">
            {CAPABILITIES.map((c) => (
              <div key={c.name} className="kq-card p-xl">
                <div className="flex items-center gap-sm">
                  <c.icon className="size-5 text-accent" aria-hidden />
                  <h3 className="font-display text-h2 text-text-primary">{c.name}</h3>
                  <span className="kq-chip ml-auto">{c.count} 工具</span>
                </div>
                <p className="mt-sm text-body-sm text-text-secondary">{c.desc}</p>
                <ul className="mt-md flex flex-col gap-xs">
                  {c.bullets.map((b) => (
                    <li key={b} className="flex items-center gap-xs text-caption text-text-secondary">
                      <span className="size-1 rounded-full bg-accent" aria-hidden />
                      {b}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ────────────── Get started(01/02/03) ────────────── */}
      <section id="install" className="border-b border-border-soft bg-surface-card-2">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            kicker="GET STARTED"
            title="三步接入,从零到实时数据"
            desc="启动后端 + 签 PAT → 接入 AI 或装 CLI → 自然语言查行情,几分钟完成。"
            docHref={docUrl('docs/quickstart.md')}
            docLabel="查看快速上手"
          />
          <ol className="mt-xl grid gap-lg md:grid-cols-3">
            {STEPS.map((s) => (
              <li key={s.n} className="kq-card p-xl">
                <span className="font-display text-display text-accent" aria-hidden>{s.n}</span>
                <h3 className="mt-xs font-display text-h2 text-text-primary">{s.title}</h3>
                <p className="mt-xs text-body-sm text-text-secondary">{s.desc}</p>
              </li>
            ))}
          </ol>
          <div className="mt-xl kq-card p-xl">
            <p className="text-label-caps text-text-muted">Claude Code 一行接入</p>
            <pre className="mt-md kq-code-block">
              <code>
                <span className="text-accent">$ </span><span className="text-text-primary">claude mcp add --transport http kwikquant http://localhost:8080/mcp \</span>{'\n'}
                <span className="text-text-primary">  --header "Authorization: Bearer &lt;YOUR_PAT&gt;"</span>
              </code>
            </pre>
            <p className="mt-md text-body-sm text-text-secondary">
              重启 Claude Code,自然语言说"列出我的交易所账户",应触发{' '}
              <code className="font-mono text-mono text-accent">list_accounts</code> 工具返回真实账户(不含 apiKey)。
            </p>
          </div>
        </div>
      </section>

      {/* ────────────── 场景演示(场景化) ────────────── */}
      <section className="border-b border-border-soft">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            kicker="场景演示"
            title="模拟盘自然语言下单,全闭环"
            desc="在模拟盘上用自然语言下单,Agent 调用 MCP 工具完成查行情 → 风控 → 下单 → 回执,实盘高危操作二次确认。"
            docHref={docUrl('docs/cookbook.md')}
            docLabel="查看更多场景"
          />
          <div className="mt-xl kq-card p-xl">
            <div className="flex items-start gap-sm py-sm">
              <span className="kq-chip kq-chip--accent">用户</span>
              <p className="font-mono text-mono text-text-secondary">在模拟盘上,okx 市价单买 0.001 BTC/USDT 现货</p>
            </div>
            <div className="flex items-start gap-sm py-sm">
              <span className="kq-chip kq-chip--info">工具</span>
              <p className="font-mono text-mono text-text-secondary">→ submit_order(symbol=BTC/USDT, side=buy, type=market, amount=0.001, marketType=spot)</p>
            </div>
            <div className="flex items-start gap-sm py-sm">
              <span className="kq-chip kq-chip--up">结果</span>
              <p className="font-mono text-mono text-text-secondary">OrderView {'{'} status: FILLED, filledQty: 0.001, filledAvgPrice: 64250.0, fee: 0.001285 USDT {'}'}</p>
            </div>
            <div className="mt-md flex items-center gap-sm border-t border-border-soft pt-md text-body-sm text-text-secondary">
              <Workflow className="size-4 text-up" aria-hidden />
              模拟盘真实成交可逆;实盘不可逆,高危操作(实盘启动 / 紧急停止)须{' '}
              <code className="font-mono text-mono text-accent">confirm=true</code>。
            </div>
          </div>
        </div>
      </section>

      {/* ────────────── AI Ready(给 AI agent 用的全量上下文) ────────────── */}
      <section id="ai-ready" className="border-b border-border-soft bg-surface-card-2">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <SectionTitle
            kicker="AI READY"
            title="给 AI agent 一次读完的全量上下文"
            desc="遵循 Anthropic llms.txt proposal:llms.txt 站点大纲 + llms-full.txt 全量单页 markdown,AI agent 一次加载即可理解全部能力;OpenAPI 3 规范运行时可取。"
          />
          <div className="mt-xl flex flex-wrap gap-sm">
            <a href={docUrl('docs/llms.txt')} target="_blank" rel="noopener noreferrer" className="kq-chip">
              llms.txt(大纲)
            </a>
            <a href={docUrl('docs/llms-full.txt')} target="_blank" rel="noopener noreferrer" className="kq-chip kq-chip--accent">
              llms-full.txt(全量)
            </a>
            <a href="http://localhost:8080/v3/api-docs" target="_blank" rel="noopener noreferrer" className="kq-chip">
              OpenAPI /v3/api-docs
            </a>
            <a href={docUrl('docs/llm-integration.md')} target="_blank" rel="noopener noreferrer" className="kq-chip">
              AI 接入选型
            </a>
          </div>
          <p className="mt-lg text-body-sm text-text-secondary">
            AI agent 实际访问 <code className="font-mono text-mono text-accent">llms-full.txt</code> 的频率是大纲{' '}
            <code className="font-mono text-mono text-accent">llms.txt</code> 的 2 倍以上(Mintlify + Profound 数据)——
            全量单页让模型一次拿到完整能力图谱,不必逐页爬取。
          </p>
        </div>
      </section>

      {/* ────────────── Footer(四分栏) ────────────── */}
      <footer className="bg-surface-canvas">
        <div className="mx-auto max-w-6xl px-lg py-xl">
          <div className="grid grid-cols-2 gap-lg md:grid-cols-4">
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
            <div className="flex flex-col gap-sm sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-center gap-sm">
                <BrandMark className="h-6 w-auto" />
                <span className="font-display text-h3">KwikQuant</span>
                <span className="text-caption text-text-muted">© 2026</span>
                <span className="ml-sm flex items-center gap-xs">
                  <span className="kq-status-dot bg-up" aria-hidden />
                  <span className="text-caption text-text-secondary">All systems operational</span>
                </span>
              </div>
              <div className="flex items-center gap-xs">
                <span className="kq-chip kq-chip--accent">中文</span>
                <span className="kq-chip">EN</span>
              </div>
            </div>
            <p className="text-caption text-text-muted">
              本地起步(localhost),公网分发是后续工作。模拟盘免费,实盘请谨慎。
            </p>
          </div>
        </div>
      </footer>
    </div>
  )
}
