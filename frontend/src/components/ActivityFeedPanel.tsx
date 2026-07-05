import { EmptyState } from './EmptyState'

/**
 * ActivityFeedPanel — 最近活动面板(spec §5 step 11 stub)。
 * 批 1a 只渲染空状态占位;批 2 接 WS notificationsStore 实时活动流。
 */
export function ActivityFeedPanel() {
  return (
    <EmptyState
      illustration={<span className="text-body">活动流</span>}
      title="暂无最近活动"
      description="策略运行后,成交与风控事件将在此实时展示"
    />
  )
}
