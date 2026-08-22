import { z } from 'zod'

/**
 * 注册表单 schema。
 * 后端 RegisterRequest:username(3-64) + email + password(8-128)。
 * 纯空白输入后端 @NotBlank 拒(3001)——前端 refine 提前拦截,
 * 避免落进「用户名或邮箱已被使用」等错位文案(与 loginSchema 保持一致)。
 */
export const registerSchema = z
  .object({
    username: z
      .string()
      .min(3, '用户名至少 3 字符')
      .max(64, '用户名最多 64 字符')
      .refine((v) => v.trim().length > 0, '用户名不能全为空格'),
    email: z.string().email('邮箱格式不正确'),
    password: z
      .string()
      .min(8, '密码至少 8 字符')
      .max(128, '密码最多 128 字符')
      .refine((v) => v.trim().length > 0, '密码不能全为空格'),
    confirmPassword: z.string().min(1, '请再次输入密码'),
    inviteCode: z
      .string()
      .min(1, '请输入邀请码')
      .refine((v) => v.trim().length > 0, '邀请码不能全为空格'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: '两次密码不一致',
    path: ['confirmPassword'],
  })

export type RegisterInput = z.infer<typeof registerSchema>
