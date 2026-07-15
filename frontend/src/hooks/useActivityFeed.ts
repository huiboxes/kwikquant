import { useQuery } from '@tanstack/react-query'
import { fetchActivityFeed, type ActivityFeedItemDto } from '@/api/activity'
import { activityKeys } from '@/api/_queryKeys'

/** 活动流(Dashboard Activity Feed 用)。 */
export function useActivityFeed(limit = 10) {
  return useQuery({
    queryKey: activityKeys.feed(limit),
    queryFn: () => fetchActivityFeed(limit),
    refetchInterval: 30_000,
  })
}

export type { ActivityFeedItemDto }
