import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

export type ActivityFeedItemDto = components['schemas']['ActivityFeedItemDto']

/** 获取活动流(GET /api/v1/activity-feed?limit=N)。 */
export function fetchActivityFeed(limit = 10): Promise<ActivityFeedItemDto[]> {
  return apiFetch<ActivityFeedItemDto[]>(`/api/v1/activity-feed?limit=${limit}`)
}
