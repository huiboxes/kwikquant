import { z } from 'zod'

/**
 * 登录表单 schema。
 * 后端 LoginRequest:{username(@NotBlank), password(@NotBlank @Size(max=128))}。
 * 前端镜像后端约束(长度上限 + 非纯空白)：校验型 3001 在提交前拦截,
 * 避免边缘输入穿透到后端后落进「服务暂时不可用」兜底文案误导用户。
 * 注意只校验不 trim 变换值(合法密码可含首尾空格)。
 */
export const loginSchema = z.object({
  username: z
    .string()
    .min(1, '请输入用户名')
    .refine((v) => v.trim().length > 0, '用户名不能全为空格'),
  password: z
    .string()
    .min(1, '请输入密码')
    .max(128, '密码最多 128 字符')
    .refine((v) => v.trim().length > 0, '密码不能全为空格'),
})

export type LoginInput = z.infer<typeof loginSchema>
