import { useEffect, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { Moon, Sun, Bell, Plus, Activity, ShieldAlert, Heart } from 'lucide-react'
import {
  CommandDialog,
  CommandInput,
  CommandList,
  CommandEmpty,
  CommandGroup,
  CommandItem,
  CommandShortcut,
} from '@/components/ui/command'
import { useUiStore } from '@/stores/uiStore'
import { useThemeStore } from '@/stores/themeStore'
import { useWatchlistStore } from '@/stores/watchlistStore'
import { useMarketTickers } from '@/hooks/useMarketTickers'
import { useStrategies } from '@/hooks/useStrategies'
import { useAccounts } from '@/hooks/useAccounts'
import { isStockToken } from '@/lib/stockTokens'
import { stripContractSuffix } from '@/lib/symbol'
import { Chip } from '@/components/Chip'
import { NAV_ITEMS } from './navItems'
import { toast } from 'sonner'

/**
 * CommandMenu — ⌘K 命令面板(照原型 AppLayout.jsx CommandPalette + commands)。
 *
 * 三组命令:
 *  - 标的(GET /market/tickers 成交额降序前 200；选中 → navigate /trade?symbol=)
 *  - 导航(NAV_ITEMS → 跳转)
 *  - 操作(切主题/开通知/新建策略/回测/紧急停止)
 *
 * 开关态在 uiStore.cmdOpen(TopBar 搜索触发器 + 本组件 ⌘K listener 都开)。
 *
 * 标的数据源基准交易所 = paper 账户 exchange(同 MarketPage 取法，兜底 OKX)。用 /market/tickers 而非
 * /market/pairs:tickers 已按 quoteVolume 降序 + search like 过滤 + 10s 缓存，主流标的(BTC/ETH/SOL)
 * 必在前 200;/pairs 是无序全量 + 前端 slice(200) 会截掉主流标的(OKX/Binance SPOT 上千条),
 * 搜 BTC 反被 fuzzy 误匹配到含 BC 字符的冷门标的(如 ZBCN/USDT)。同 MarketPage 数据源。
 */
export function CommandMenu() {
  const navigate = useNavigate()
  const cmdOpen = useUiStore((s) => s.cmdOpen)
  const setCmdOpen = useUiStore((s) => s.setCmdOpen)
  const setNotifOpen = useUiStore((s) => s.setNotifOpen)
  const colorScheme = useThemeStore((s) => s.colorScheme)
  const toggleColorScheme = useThemeStore((s) => s.toggleColorScheme)

  // 基准交易所(paper 账户 exchange，兜底 OKX，同 MarketPage 取法)→ useMarketTickers 拉成交额降序前 200 供 ⌘K 搜
  const { data: accounts } = useAccounts()
  const exchange = useMemo(
    () => (accounts ?? []).find((a) => a.paperTrading)?.exchange ?? 'OKX',
    [accounts],
  )
  const { data: tickers } = useMarketTickers({ exchange, marketType: 'SPOT', limit: 200 })
  const watchlist = useWatchlistStore((s) => s.symbols)
  const { data: strategies = [] } = useStrategies()
  // 标的命令:useMarketTickers 已按成交额降序取前 200(BTC/ETH/SOL 主流必在前)，无需前端 slice。
  // value=sym:cmdk 子串匹配搜 BTC → 命中 "BTC/USDT"；不拼 base/quote(Ticker 无此字段，symbol 自足)。
  const symbolCommands = useMemo(
    () =>
      (tickers ?? [])
        .map((t) => t.ticker.symbol)
        .filter((sym): sym is string => !!sym)
        .map((sym) => ({
          id: 'sym-' + sym,
          label: sym,
          value: sym,
          action: () => navigate(`/trade?symbol=${encodeURIComponent(sym)}`),
        })),
    [tickers, navigate],
  )

  // ⌘K / Ctrl+K 打开
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setCmdOpen(true)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [setCmdOpen])

  const navCommands = NAV_ITEMS.map((it) => ({
    id: 'go-' + it.id,
    label: '跳转：' + it.label,
    Icon: it.icon,
    action: () => navigate(it.to),
  }))

  const actionCommands = [
    {
      id: 'theme',
      label: '切换深 / 浅主题',
      Icon: colorScheme === 'dark' ? Sun : Moon,
      action: () => toggleColorScheme(),
      hint: 'T',
    },
    { id: 'notif', label: '打开通知', Icon: Bell, action: () => setNotifOpen(true) },
    {
      id: 'newstrat',
      label: '新建策略',
      Icon: Plus,
      action: () => {
        navigate('/strategy')
        toast.success('新建策略', { description: '从草稿开始，AI 助手陪你编写代码' })
      },
    },
    {
      id: 'backtest',
      label: '提交新回测',
      Icon: Activity,
      action: () => {
        navigate('/backtest')
        toast.success('回测', { description: '选择策略与周期后提交' })
      },
    },
    {
      id: 'stop',
      label: '紧急停止 · 高风险',
      Icon: ShieldAlert,
      action: () => {
        navigate('/risk')
        toast.error('紧急停止', { description: '将拦截所有实盘下单' })
      },
    },
  ]

  return (
    <CommandDialog
      open={cmdOpen}
      onOpenChange={setCmdOpen}
      title="命令面板"
      description="搜索标的 / 页面 / 命令"
    >
      <CommandInput placeholder="搜索策略 / 标的 / 页面 / 命令…" />
      <CommandList>
        <CommandEmpty>没有匹配的策略 / 标的 / 命令</CommandEmpty>
        {watchlist.length > 0 && (
          <CommandGroup heading="自选">
            {watchlist.map((s) => (
              <CommandItem
                key={'wl-' + s}
                value={s}
                onSelect={() => {
                  navigate(`/trade?symbol=${encodeURIComponent(s)}`)
                  setCmdOpen(false)
                }}
              >
                <Heart className="h-[16px] w-[16px]" aria-hidden />
                <span className="kq-mono-row">{s}</span>
              </CommandItem>
            ))}
          </CommandGroup>
        )}
        {strategies.length > 0 && (
          <CommandGroup heading="策略">
            {strategies.map((s) => (
              <CommandItem
                key={'strat-' + s.id}
                value={s.name + ' ' + stripContractSuffix(s.symbol)}
                onSelect={() => {
                  navigate(`/strategy?strategyId=${s.id}`)
                  setCmdOpen(false)
                }}
              >
                <span className="text-text-primary">{s.name}</span>
                <span className="kq-mono-row text-text-muted">· {stripContractSuffix(s.symbol)}</span>
              </CommandItem>
            ))}
          </CommandGroup>
        )}
        {symbolCommands.length > 0 && (
          <CommandGroup heading="标的">
            {symbolCommands.map((c) => (
              <CommandItem
                key={c.id}
                value={c.value}
                onSelect={() => {
                  c.action()
                  setCmdOpen(false)
                }}
              >
                <span className="kq-mono-row">{c.label}</span>
                {isStockToken(c.label) && (
                  <Chip label="股" color="warning" title="股票代币·仅现货" />
                )}
              </CommandItem>
            ))}
          </CommandGroup>
        )}
        <CommandGroup heading="导航">
          {navCommands.map((c) => (
            <CommandItem
              key={c.id}
              value={c.label}
              onSelect={() => {
                c.action()
                setCmdOpen(false)
              }}
            >
              <c.Icon className="h-[16px] w-[16px]" />
              <span>{c.label}</span>
            </CommandItem>
          ))}
        </CommandGroup>
        <CommandGroup heading="操作">
          {actionCommands.map((c) => (
            <CommandItem
              key={c.id}
              value={c.label}
              onSelect={() => {
                c.action()
                setCmdOpen(false)
              }}
            >
              <c.Icon className="h-[16px] w-[16px]" />
              <span>{c.label}</span>
              {c.hint && <CommandShortcut>{c.hint}</CommandShortcut>}
            </CommandItem>
          ))}
        </CommandGroup>
      </CommandList>
    </CommandDialog>
  )
}
