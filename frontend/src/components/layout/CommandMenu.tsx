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
import { usePairs } from '@/hooks/useMarket'
import { useAccounts } from '@/hooks/useAccounts'
import { NAV_ITEMS } from './navItems'
import { toast } from 'sonner'

/**
 * CommandMenu — ⌘K 命令面板(照原型 AppLayout.jsx CommandPalette + commands)。
 *
 * 三组命令:
 *  - 标的(GET /market/pairs 全量,active 过滤;选中 → navigate /market?symbol=)
 *  - 导航(NAV_ITEMS → 跳转)
 *  - 操作(切主题/开通知/新建策略/回测/紧急停止)
 *
 * 开关态在 uiStore.cmdOpen(TopBar 搜索触发器 + 本组件 ⌘K listener 都开)。
 *
 * 标的数据源基准交易所 = paper 账户 exchange(同 MarketPage 取法,兜底 OKX)。/pairs Caffeine 1h 缓存,
 * 按需搜索不增后端常驻压力(非 persistent symbol 看行情走 REST fallback + 按需 WS worker)。
 * 超大交易所(币安 SPOT 上千条)前端 slice(200) 兜底 cmdk filter 性能,后续后端可加 ?q= 参数。
 */
export function CommandMenu() {
  const navigate = useNavigate()
  const cmdOpen = useUiStore((s) => s.cmdOpen)
  const setCmdOpen = useUiStore((s) => s.setCmdOpen)
  const setNotifOpen = useUiStore((s) => s.setNotifOpen)
  const colorScheme = useThemeStore((s) => s.colorScheme)
  const toggleColorScheme = useThemeStore((s) => s.toggleColorScheme)

  // 基准交易所(paper 账户 exchange,兜底 OKX,同 MarketPage 取法)→ usePairs 拉全量标的供 ⌘K 搜
  const { data: accounts } = useAccounts()
  const exchange = useMemo(
    () => (accounts ?? []).find((a) => a.paperTrading)?.exchange ?? 'OKX',
    [accounts],
  )
  const { data: pairs } = usePairs(exchange, 'SPOT')
  const watchlist = useWatchlistStore((s) => s.symbols)
  // 标的命令:active 过滤 + slice(200) 兜底超大交易所;value 含 baseAsset/quoteAsset 供搜(输 eth → ETH/USDT)。
  const symbolCommands = useMemo(
    () =>
      (pairs ?? [])
        .filter((p) => p.active && p.symbol)
        .slice(0, 200)
        .map((p) => {
          const sym = p.symbol!
          return {
            id: 'sym-' + sym,
            label: sym,
            value: `${sym} ${p.baseAsset ?? ''} ${p.quoteAsset ?? ''}`,
            action: () => navigate(`/market?symbol=${encodeURIComponent(sym)}`),
          }
        }),
    [pairs, navigate],
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
    { id: 'notif', label: '打开通知抽屉', Icon: Bell, action: () => setNotifOpen(true) },
    {
      id: 'newstrat',
      label: '新建策略(跳转工作台)',
      Icon: Plus,
      action: () => {
        navigate('/strategy')
        toast.success('新建策略', { description: '从草稿开始 · AI 流式辅助' })
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
      <CommandInput placeholder="搜索标的 / 页面 / 命令…" />
      <CommandList>
        <CommandEmpty>没有匹配的标的 / 命令</CommandEmpty>
        {watchlist.length > 0 && (
          <CommandGroup heading="自选">
            {watchlist.map((s) => (
              <CommandItem
                key={'wl-' + s}
                value={s}
                onSelect={() => {
                  navigate(`/market?symbol=${encodeURIComponent(s)}`)
                  setCmdOpen(false)
                }}
              >
                <Heart className="h-[16px] w-[16px]" aria-hidden />
                <span className="kq-mono-row">{s}</span>
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
