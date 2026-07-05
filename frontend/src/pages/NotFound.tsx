import { Link } from 'react-router-dom'

/**
 * 404 页 — 未匹配路由。
 */
export function NotFound() {
  return (
    <div className="mx-auto max-w-[1240px] px-xl py-3xl text-center text-text-primary">
      <p className="text-label-caps text-text-muted uppercase">404</p>
      <h1 className="mt-md font-display text-display">页面不存在</h1>
      <p className="mt-lg font-body text-body text-text-secondary">
        你访问的页面不在批 1a 路由表内,或已被移除。
      </p>
      <Link
        to="/"
        className="mt-xl inline-flex items-center rounded-md bg-primary px-lg py-md font-body text-body-sm text-accent transition-colors hover:bg-onyx"
      >
        返回首页
      </Link>
    </div>
  )
}
