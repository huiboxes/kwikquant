import { existsSync, mkdirSync, readFileSync, writeFileSync, unlinkSync, chmodSync, statSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

/**
 * Credentials — 本地凭证存储(~/.kwikquant/credentials.json)。
 *
 * 存 baseUrl + JWT(+ username / expiresAt 元信息)。
 * CLI 直连 REST(/api/v1/**),走 JWT 鉴权(JwtAuthenticationFilter);
 * PAT 仅 MCP client 走 /mcp/** 用,CLI 不需要 PAT。
 *
 * - 目录 0700、文件 0600(仅属主可读)
 * - 写后强制 chmod(防 umask 把权限放宽)
 * - 读时 checkPermissions 校验,不达标警告
 */
export interface Credentials {
  baseUrl: string
  jwt: string
  username?: string
  expiresAt?: string
}

const CREDENTIALS_DIR = join(homedir(), '.kwikquant')
export const CREDENTIALS_FILE = join(CREDENTIALS_DIR, 'credentials.json')

export function loadCredentials(): Credentials | null {
  if (!existsSync(CREDENTIALS_FILE)) return null
  try {
    const raw = readFileSync(CREDENTIALS_FILE, 'utf-8')
    const parsed = JSON.parse(raw) as Partial<Credentials>
    if (!parsed.jwt || !parsed.baseUrl) return null
    return parsed as Credentials
  } catch {
    return null
  }
}

export function saveCredentials(creds: Credentials): void {
  if (!existsSync(CREDENTIALS_DIR)) {
    mkdirSync(CREDENTIALS_DIR, { recursive: true, mode: 0o700 })
  }
  writeFileSync(CREDENTIALS_FILE, JSON.stringify(creds, null, 2) + '\n', { mode: 0o600 })
  chmodSync(CREDENTIALS_FILE, 0o600)
}

export function clearCredentials(): void {
  try {
    unlinkSync(CREDENTIALS_FILE)
  } catch {
    // 不存在即可
  }
}

export function assertAuthed(): Credentials {
  const creds = loadCredentials()
  if (!creds?.jwt) {
    throw new Error('未登录。请先 `kwikquant auth login <username> <password>`。')
  }
  if (creds.expiresAt && Date.parse(creds.expiresAt) <= Date.now()) {
    throw new Error('JWT 已过期,请重新 `kwikquant auth login`。')
  }
  return creds
}

/** 校验 credentials 文件权限(0600),不达标 stderr 警告(不阻塞)。 */
export function checkPermissions(): void {
  if (!existsSync(CREDENTIALS_FILE)) return
  const mode = statSync(CREDENTIALS_FILE).mode & 0o777
  if (mode !== 0o600) {
    process.stderr.write(
      `⚠ ${CREDENTIALS_FILE} 权限 ${mode.toString(8).padStart(4, '0')},建议 0600(仅属主可读)\n`,
    )
  }
}
