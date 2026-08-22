/**
 * 认证页回跳目标处理（?from=）。
 *
 * 守卫(RequireAuth/RootGuard)把受保护路径写入 /login?from=…（{@link loginUrlFor}），
 * 登录/注册成功后回到原页面。from 经 URL 传递即用户可控，读取侧必须过
 * {@link sanitizeRedirectTarget} 防开放重定向：
 * - 必须以 / 开头（拒 https://evil.com 等绝对 URL）
 * - 不得 // 开头或含 \（//evil.com 与 /\evil.com 均为 protocol-relative 变体）
 * - 不得含 C0 控制字符（%0A 解码出换行等：浏览器 URL 解析会剥离换行把 /\n/evil.com 还原成 //evil.com）
 * - 不得指向 /login /register 自身（大小写不敏感 + 容忍尾斜杠 + 先剥 hash/query，
 *   与 react-router 默认 caseSensitive:false 的路由匹配行为一致，防认证页间重定向循环）
 * 非法返 null，调用方兜底 '/'。
 */
export function sanitizeRedirectTarget(raw: string | null | undefined): string | null {
  if (!raw) return null
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.includes('\\')) return null
  // 拒 C0 控制字符(< 0x20,含 \n \r \t):防 URL 解析剥离后还原出 protocol-relative
  for (let i = 0; i < raw.length; i++) {
    if (raw.charCodeAt(i) < 0x20) return null
  }
  const path = raw.split('?')[0].split('#')[0]
  if (/^\/(login|register)\/?$/i.test(path)) return null
  return raw
}

/** 构造带 from 的认证页 URL；target 为空或非法时返回裸路径（不携带 from）。 */
export function authUrlWithFrom(base: '/login' | '/register', from: string | null | undefined): string {
  const clean = sanitizeRedirectTarget(from)
  return clean == null ? base : `${base}?from=${encodeURIComponent(clean)}`
}

/**
 * 守卫写侧：由当前受保护 location 构造 /login?from=…（RequireAuth/RootGuard 共用，单一拼法）。
 * 保留 pathname + search（深链参数不丢）；不带 hash（受保护页无锚点导航场景）。
 */
export function loginUrlFor(location: { pathname: string; search: string }): string {
  return `/login?from=${encodeURIComponent(location.pathname + location.search)}`
}
