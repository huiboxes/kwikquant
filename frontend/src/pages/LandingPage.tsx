import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { BrandMark } from '@/components/BrandMark'
import {
  ArrowRight,
  ArrowRightLeft,
  CandlestickChart,
  CheckCircle2,
  FlaskConical,
  Package,
  Server,
  ShieldCheck,
  Terminal,
  Wallet,
} from 'lucide-react'

/**
 * LandingPage — 公开营销首页(未登录访问 / 时显)。
 *
 * 抄长桥 open.longbridge.com/zh-CN 的结构,适配加密域:
 * Hero → 数据统计 → 三种接入(MCP/Skill/CLI)→ 能力目录 5 域 → 三步安装 → 场景演示 → Footer。
 * 视觉严格走 DESIGN.md token(primary 暖橙 + Cormorant Garamond display + 暖白画布),零硬编码颜色/圆角/字号。
 */
const STATS = [
  { value: '3', label: '交易所' },
  { value: '23', label: 'MCP 工具' },
  { value: '5', label: 'Skill 包' },
  { value: '$0', label: '模拟盘接入费' },
] as const

const INTEGRATIONS = [
  {
    icon: Server,
    name: 'MCP Server',
    desc: 'Streamable HTTP + PAT 鉴权。Claude Code / Cursor 一行命令接入,23 工具动态发现,无需手动配置。',
    code: 'claude mcp add --transport http kwikquant http://localhost:8080/mcp',
  },
  {
    icon: Package,
    name: 'Skill 包',
    desc: '5 个 Anthropic Agent Skills,按域分包:行情 / 账户 / 下单 / 策略 / 风控,可被任意 MCP 客户端调用。',
    code: 'npx skills add kwikquant/skills -g',
  },
  {
    icon: Terminal,
    name: 'CLI',
    desc: '命令行直连后端,--format json 可管道 jq / awk,SSH 无头环境与 Docker 容器友好。',
    code: 'kwikquant quote BTC/USDT --format json',
  },
] as const

const CAPABILITIES = [
  {
    icon: CandlestickChart,
    name: '行情数据',
    count: 4,
    tools: 'K线 · 最新价 · 盘口 · 资金费率',
    desc: 'OKX / Binance / Bitget 实时行情,SPOT + PERP 永续。',
  },
  {
    icon: Wallet,
    name: '账户与组合',
    count: 4,
    tools: '账户 · 余额 · 组合 · 交易历史',
    desc: '跨账户资产总览,apiKey 隔离不暴露给 Agent。',
  },
  {
    icon: ArrowRightLeft,
    name: '下单与持仓',
    count: 7,
    tools: '下单 · 撤单 · 持仓 · 平仓 · 资金费 · 强平',
    desc: 'SPOT + PERP,经风控网关,高危操作二次确认。',
  },
  {
    icon: FlaskConical,
    name: '策略与回测',
    count: 5,
    tools: '回测 · 对比 · 模拟盘 · 实盘',
    desc: '回测 → 对比 → 模拟 → 实盘,渐进上线。',
  },
  {
    icon: ShieldCheck,
    name: '风控',
    count: 3,
    tools: '查 · 设规则 · 紧急停止',
    desc: '最大下单额 / 日亏损 / 频率,紧急停止 fail-closed 审计。',
  },
] as const

const STEPS = [
  { n: '1', title: '启动后端', desc: './mvnw spring-boot:run,MCP server 暴露在 http://localhost:8080/mcp' },
  { n: '2', title: '签发 PAT', desc: '登录前端 → Settings → MCP Tokens,复制明文 token(仅此一次)' },
  { n: '3', title: '配 AI 客户端', desc: 'Claude Code 加 mcpServers,重启即用' },
] as const

const DEMO = [
  { role: '用户', text: '在模拟盘上,okx 市价单买 0.001 BTC/USDT 现货', chip: 'accent' as const },
  { role: '工具', text: '→ submit_order(exchange=okx, symbol=BTC/USDT, side=buy, type=market, amount=0.001, marketType=spot)', chip: 'info' as const },
  { role: '结果', text: 'OrderView { status: FILLED, filledQty: 0.001, avgPrice: 64250.0, fee: 0.001285 USDT }', chip: 'up' as const },
] as const

const chipClass = {
  accent: 'kq-chip kq-chip--accent',
  info: 'kq-chip kq-chip--info',
  up: 'kq-chip kq-chip--up',
} as const

export function LandingPage() {
  return (
    <div className="min-h-screen bg-surface-canvas font-body text-text-primary">
      {/* Nav */}
      <header className="sticky top-0 z-50 border-b border-border-soft bg-surface-canvas/80 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-lg">
          <Link to="/" className="flex items-center gap-sm" aria-label="KwikQuant 首页">
            <BrandMark className="h-7 w-auto" />
            <span className="font-display text-h2">KwikQuant</span>
          </Link>
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

      {/* Hero */}
      <section className="relative overflow-hidden">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <div className="max-w-3xl">
            <span className="kq-chip kq-chip--accent">加密货币量化交易 · MCP SERVER</span>
            <h1 className="mt-lg font-display text-display text-text-primary">
              AI 直连真实行情
            </h1>
            <p className="mt-lg max-w-2xl text-body text-text-secondary">
              通过 MCP / Skill / CLI 一体接入 OKX / Binance / Bitget,SPOT 现货与永续合约 PERP(杠杆 / 保证金模式 / 资金费率 8h 结算 / 强平)。
              一套凭证覆盖 23 个工具,模拟盘免费验证,实盘高危操作二次确认。
            </p>
            <div className="mt-xl flex flex-wrap gap-sm">
              <Button size="lg" asChild>
                <Link to="/register">
                  开始使用 <ArrowRight className="size-4" aria-hidden />
                </Link>
              </Button>
              <Button variant="outline" size="lg" asChild>
                <a href="#install">安装 MCP</a>
              </Button>
            </div>
          </div>
          {/* 数据统计 */}
          <dl className="mt-xxl grid grid-cols-2 gap-lg sm:grid-cols-4">
            {STATS.map((s) => (
              <div key={s.label} className="kq-card p-lg">
                <dt className="font-display text-h1 text-accent">{s.value}</dt>
                <dd className="mt-xs text-caption text-text-muted">{s.label}</dd>
              </div>
            ))}
          </dl>
        </div>
      </section>

      {/* Integrations */}
      <section className="border-t border-border-soft">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <h2 className="font-display text-h1 text-text-primary">三种接入方式</h2>
          <p className="mt-sm max-w-2xl text-body text-text-secondary">
            一套后端,三种消费路径。AI 客户端走 MCP,Agent 走 Skill 分包,终端走 CLI。
          </p>
          <div className="mt-xl grid gap-lg md:grid-cols-3">
            {INTEGRATIONS.map((it) => (
              <div key={it.name} className="kq-card p-xl">
                <it.icon className="size-6 text-accent" aria-hidden />
                <h3 className="mt-md font-display text-h2 text-text-primary">{it.name}</h3>
                <p className="mt-xs text-body-sm text-text-secondary">{it.desc}</p>
                <pre className="mt-md overflow-x-auto rounded-md bg-surface-card-2 p-md font-mono text-mono text-text-secondary">
                  <code>{it.code}</code>
                </pre>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Capabilities */}
      <section id="capabilities" className="border-t border-border-soft bg-surface-card-2">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <h2 className="font-display text-h1 text-text-primary">能力目录</h2>
          <p className="mt-sm max-w-2xl text-body text-text-secondary">
            23 个工具按 5 个域分包,每个 Skill 都是一套打包工具集,可被任意 MCP 客户端调用。
          </p>
          <div className="mt-xl grid gap-lg md:grid-cols-2 lg:grid-cols-3">
            {CAPABILITIES.map((c) => (
              <div key={c.name} className="kq-card p-xl">
                <div className="flex items-center gap-sm">
                  <c.icon className="size-5 text-accent" aria-hidden />
                  <h3 className="font-display text-h2 text-text-primary">{c.name}</h3>
                  <span className="kq-chip ml-auto">{c.count} 工具</span>
                </div>
                <p className="mt-sm text-body-sm text-text-secondary">{c.desc}</p>
                <p className="mt-md font-mono text-caption text-text-muted">{c.tools}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Install */}
      <section id="install" className="border-t border-border-soft">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <h2 className="font-display text-h1 text-text-primary">三步接入</h2>
          <ol className="mt-xl grid gap-lg md:grid-cols-3">
            {STEPS.map((s) => (
              <li key={s.n} className="kq-card p-xl">
                <span className="font-display text-h1 text-accent" aria-hidden>
                  {s.n}
                </span>
                <h3 className="mt-xs font-display text-h2 text-text-primary">{s.title}</h3>
                <p className="mt-xs text-body-sm text-text-secondary">{s.desc}</p>
              </li>
            ))}
          </ol>
          <div className="mt-xl kq-card p-xl">
            <p className="text-label-caps text-text-muted">Claude Code 一行接入</p>
            <pre className="mt-md overflow-x-auto rounded-md bg-surface-card-2 p-md font-mono text-mono text-text-secondary">
              <code>{`claude mcp add --transport http kwikquant http://localhost:8080/mcp \\
  --header "Authorization: Bearer <YOUR_PAT>"`}</code>
            </pre>
            <p className="mt-md text-body-sm text-text-secondary">
              重启 Claude Code,自然语言说"列出我的交易所账户",应触发{' '}
              <code className="font-mono text-mono">list_accounts</code> 工具返回真实账户(不含 apiKey)。
            </p>
          </div>
        </div>
      </section>

      {/* Demo */}
      <section className="border-t border-border-soft bg-surface-card-2">
        <div className="mx-auto max-w-6xl px-lg py-section">
          <h2 className="font-display text-h1 text-text-primary">场景演示</h2>
          <p className="mt-sm max-w-2xl text-body text-text-secondary">
            在模拟盘上用自然语言下单,Agent 调用 MCP 工具完成全闭环。
          </p>
          <div className="mt-xl kq-card p-xl">
            {DEMO.map((d, i) => (
              <div key={i} className="flex items-start gap-sm py-sm">
                <span className={chipClass[d.chip]}>{d.role}</span>
                <p className="font-mono text-mono text-text-secondary">{d.text}</p>
              </div>
            ))}
            <div className="mt-md flex items-center gap-sm border-t border-border-soft pt-md text-body-sm text-text-secondary">
              <CheckCircle2 className="size-4 text-up" aria-hidden />
              模拟盘真实成交可逆;实盘不可逆,高危操作(实盘启动 / 紧急停止)须{' '}
              <code className="font-mono text-mono">confirm=true</code>。
            </div>
          </div>
        </div>
      </section>

      {/* Footer */}
      <footer className="border-t border-border-soft">
        <div className="mx-auto max-w-6xl px-lg py-xl">
          <div className="flex flex-col gap-md sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-center gap-sm">
              <BrandMark className="h-6 w-auto" />
              <span className="font-display text-h3">KwikQuant</span>
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
