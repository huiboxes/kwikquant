import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearPrivateSession } from './clearPrivateSession'
import { queryClient } from './queryClient'
import { getWsConnection } from './ws/ConnectionManager'
import { useAuthStore } from '@/stores/authStore'
import { useNotifStore } from '@/stores/notifStore'

describe('clearPrivateSession', () => {
  beforeEach(() => {
    queryClient.clear()
    useNotifStore.getState().clear()
    useAuthStore.setState({
      status: 'authenticated',
      accessToken: 'private-token',
      user: { userId: 1, username: 'alice' },
    })
  })

  it('清空私有 Query 缓存、通知和认证并断开 WS', () => {
    queryClient.setQueryData(['private-account'], { balance: '100' })
    useNotifStore.getState().addNotification({
      id: 'n1', type: 'fill', title: '订单成交', body: '私有通知', ts: 'now', unread: true,
    })
    const disconnect = vi.spyOn(getWsConnection(), 'disconnect')

    clearPrivateSession()

    expect(queryClient.getQueryData(['private-account'])).toBeUndefined()
    expect(useNotifStore.getState().notifications).toEqual([])
    expect(useAuthStore.getState()).toMatchObject({ status: 'anonymous', accessToken: null, user: null })
    expect(disconnect).toHaveBeenCalledOnce()
  })
})
