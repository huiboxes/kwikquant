import { queryClient } from '@/lib/queryClient'
import { getWsConnection } from '@/lib/ws/ConnectionManager'
import { useAuthStore } from '@/stores/authStore'
import { useNotifStore } from '@/stores/notifStore'

/** 清除所有用户私有客户端状态，避免同一 SPA 生命周期内跨用户复用。 */
export function clearPrivateSession(): void {
  getWsConnection().disconnect()
  queryClient.clear()
  useNotifStore.getState().clear()
  useAuthStore.getState().clearAuth()
}
