import { z } from 'zod'

/**
 * 登录表单 schema(spec §5 step 7)。
 * 后端 LoginRequest:{username, password}。
 */
export const loginSchema = z.object({
  username: z.string().min(1, '请输入用户名'),
  password: z.string().min(1, '请输入密码'),
})

export type LoginInput = z.infer<typeof loginSchema>
