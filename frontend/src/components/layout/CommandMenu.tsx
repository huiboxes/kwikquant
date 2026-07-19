import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Moon, Sun, Bell, Plus, Activity, ShieldAlert } from 'lucide-react'
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
import { NAV_ITEMS } from './navItems'
import { toast } from 'sonner'

/**
 * CommandMenu — ⌘K 命令面板(照原型 AppLayout.jsx CommandPalette + commands)。
 * 导航命令(NAV_ITEMS → 跳转)+ 操作命令(切主题/开通知/新建策略/回测/紧急停止)。
 * 开关态在 uiStore.cmdOpen(TopBar 搜索触发器 + 本组件 ⌘K listener 都开)。
 */
export function CommandMenu() {
  const navigate = useNavigate()
  const cmdOpen = useUiStore((s) => s.cmdOpen)
  const setCmdOpen = useUiStore((s) => s.setCmdOpen)
  const setNotifOpen = useUiStore((s) => s.setNotifOpen)
  const colorScheme = useThemeStore((s) => s.colorScheme)
  const toggleColorScheme = useThemeStore((s) => s.toggleColorScheme)

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
      description="搜索页面 / 跳转 / 命令"
    >
      <CommandInput placeholder="搜索页面 / 跳转页面 / 命令…" />
      <CommandList>
        <CommandEmpty>没有匹配的命令</CommandEmpty>
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
