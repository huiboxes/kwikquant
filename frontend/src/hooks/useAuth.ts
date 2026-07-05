import { useAuthStore } from '@/stores/authStore'

/**
 * useAuth — 暴露认证状态给组件(spec §5 step 2)。
 *
 * status: unknown(启动探活中) | authenticated | anonymous
 * user: 从 access token payload 派生(userId/username),无 GET /auth/me
 */
export function useAuth() {
  const status = useAuthStore((s) => s.status)
  const user = useAuthStore((s) => s.user)
  return {
    status,
    user,
    isAuthenticated: status === 'authenticated',
    isAnonymous: status === 'anonymous',
  }
}
