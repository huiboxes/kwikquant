import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * auth typed client(账户与密码;SettingsPage account tab 用)。
 *
 * 端点(均 JWT):
 *  - POST /api/v1/auth/change-password  body ChangePasswordRequest{oldPassword,newPassword 8-128}
 *           → Void(旧密码错 401 1001 UNAUTHENTICATED)
 *
 * honest:本模块当前只含 changePassword 一个端点。login/register/refresh/logout 仍在
 * hooks/useAuth 裸调 /api/v1/auth/*(后续重构统一到 api/auth.ts,TD-030)。
 * SettingsPage 是 change-password 唯一消费方,本模块最小化只导出此端点,避免触碰已稳的
 * auth 链(401 单飞续期 + refresh 重放,见 behavior-contract §1.1)。
 */
type ChangePasswordRequest = components['schemas']['ChangePasswordRequest']

export type { ChangePasswordRequest }

/** 修改登录密码(POST /auth/change-password;旧密码错 401 1001)。account tab "更新密码"用。 */
export function changePassword(req: ChangePasswordRequest): Promise<void> {
  return apiFetch<void>('/api/v1/auth/change-password', { method: 'POST', body: req })
}
