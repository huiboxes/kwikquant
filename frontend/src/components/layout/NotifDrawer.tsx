import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Shield, Check, X, Play, TriangleAlert, Square } from 'lucide-react'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useUiStore } from '@/stores/uiStore'
import { useNotifStore, type NotifType } from '@/stores/notifStore'
import { toast } from 'sonner'
import { cn } from '@/lib/utils'

const ICONS: Record<NotifType, typeof Shield> = {
  risk: Shield,
  fill: Check,
  cancel: X,
  strat_start: Play,
  strat_stopped: Square,
  strat_error: TriangleAlert,
}

const TONE: Record<NotifType, string> = {
  risk: 'text-down',
  fill: 'text-up',
  cancel: 'text-text-secondary',
  strat_start: 'text-up',
  strat_stopped: 'text-text-secondary',
  strat_error: 'text-down',
}

type Tab = '全部' | '未读' | '风控' | '策略'

/**
 * NotifDrawer — 右侧通知抽屉(照原型 AppLayout.jsx NotifDrawer)。
 * Sheet(side=right)+ Tabs(全部/未读/风控/策略)+ 通知列表(接 notifStore)+ footer(全部已读/偏好)。
 * 开关态 uiStore.notifOpen(TopBar 通知钮触发)。
 * 数据源 notifStore(TD-053:WS /topic/notifications 未接,当前 mock;接后真实推送)。
 */
export function NotifDrawer() {
  const notifOpen = useUiStore((s) => s.notifOpen)
  const setNotifOpen = useUiStore((s) => s.setNotifOpen)
  const notifications = useNotifStore((s) => s.notifications)
  const markAllRead = useNotifStore((s) => s.markAllRead)
  const navigate = useNavigate()
  const [tab, setTab] = useState<Tab>('全部')

  const unread = notifications.filter((n) => n.unread).length
  const list = notifications.filter((n) => {
    if (tab === '全部') return true
    if (tab === '未读') return n.unread
    if (tab === '风控') return n.type === 'risk'
    return n.type === 'strat_start' || n.type === 'strat_stopped' || n.type === 'strat_error' // 策略
  })

  return (
    <Sheet open={notifOpen} onOpenChange={setNotifOpen}>
      <SheetContent side="right" className="flex w-[380px] max-w-[90vw] gap-0 border-transparent bg-surface-card p-0 shadow-pop">
        <SheetHeader className="flex flex-row items-center justify-between border-b border-border p-md">
          <div>
            <SheetTitle>通知</SheetTitle>
            <SheetDescription>实时推送 · {unread} 条未读</SheetDescription>
          </div>
        </SheetHeader>

        <Tabs
          value={tab}
          onValueChange={(v) => setTab(v as Tab)}
          className="flex min-h-0 flex-1 flex-col"
        >
          <div className="p-sm">
            <TabsList>
              <TabsTrigger value="全部">全部</TabsTrigger>
              <TabsTrigger value="未读">未读</TabsTrigger>
              <TabsTrigger value="风控">风控</TabsTrigger>
              <TabsTrigger value="策略">策略</TabsTrigger>
            </TabsList>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto p-sm">
            {list.length === 0 ? (
              <div className="py-xl text-center text-caption text-text-muted">没有通知</div>
            ) : (
              list.map((n) => {
                const Icon = ICONS[n.type]
                return (
                  <div
                    key={n.id}
                    className={cn('flex gap-sm border-b border-border p-sm', !n.unread && 'opacity-60')}
                  >
                    <div
                      className={cn(
                        'flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-md bg-surface-card-2',
                        TONE[n.type],
                      )}
                    >
                      <Icon className="h-[14px] w-[14px]" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-baseline justify-between">
                        <span className="text-body-sm font-semibold text-text-primary">{n.title}</span>
                        <span className="text-label-caps text-text-muted">{n.ts}</span>
                      </div>
                      <div className="mt-xxs text-caption leading-snug text-text-secondary">{n.body}</div>
                    </div>
                    {n.unread && (
                      <span className="mt-xs h-[6px] w-[6px] shrink-0 rounded-full bg-accent" />
                    )}
                  </div>
                )
              })
            )}
          </div>
        </Tabs>

        <div className="flex gap-xs border-t border-border p-md">
          <button
            type="button"
            onClick={() => {
              markAllRead()
              toast.success('已全部标记为已读')
            }}
            className="flex-1 rounded-md border border-border bg-transparent py-xs text-body-sm text-text-secondary transition-colors motion-fast hover:bg-surface-hover"
          >
            全部已读
          </button>
          <button
            type="button"
            onClick={() => {
              setNotifOpen(false)
              navigate('/settings')
            }}
            className="flex-1 rounded-md border border-border bg-transparent py-xs text-body-sm text-text-secondary transition-colors motion-fast hover:bg-surface-hover"
          >
            偏好
          </button>
        </div>
      </SheetContent>
    </Sheet>
  )
}
