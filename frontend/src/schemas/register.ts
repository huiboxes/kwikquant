import { z } from 'zod'

/**
 * 注册表单 schema(spec §5 step 7)。
 * 后端 RegisterRequest:username(3-64) + email + password(8-128)。
 */
export const registerSchema = z
  .object({
    username: z
      .string()
      .min(8, '用户名至少 8 字符')
      .max(64, '用户名最多 64 字符'),
    email: z.string().email('邮箱格式不正确'),
    password: z
      .string()
      .min(8, '密码至少 8 字符')
      .max(128, '密码最多 128 字符'),
    confirmPassword: z.string().min(1, '请再次输入密码'),
    inviteCode: z.string().min(1, '请输入邀请码'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: '两次密码不一致',
    path: ['confirmPassword'],
  })

export type RegisterInput = z.infer<typeof registerSchema>
