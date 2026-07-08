#!/usr/bin/env node
/**
 * lint:design:usage — 扫 frontend/src 里的硬编码违规
 *
 * 规则(见 CLAUDE.md §Frontend Design Contract + DESIGN.md §Do's and Don'ts):
 *   E1  硬编码 hex 颜色      #FF0000 / #ffff
 *   E2  硬编码 rgb/hsl 颜色  rgb(255,0,0) / hsla(...)
 *   E3  内联 style 常量硬编码  style={{ fontSize: '13px' }} / color: 'red'
 *   E4  var(--x) 引用未定义   var(--color-primry) 等 typo
 *   W1  Tailwind arbitrary value  text-[24px] / rounded-[16px]（脱离 scale）
 *
 * 豁免：src/components/ui/**（shadcn 生成）、src/index.css（token 源）、
 *      src/types/api-gen.ts（openapi 生成）
 *
 * exit 0 = 0 errors; exit 1 = 至少 1 error（warning 不阻塞退出码）
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { load as yamlLoad } from 'js-yaml'

const FRONTEND_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const SRC_DIR = join(FRONTEND_ROOT, 'src')
const DESIGN_MD = join(FRONTEND_ROOT, 'DESIGN.md')

const EXEMPT_PREFIXES = ['src/components/ui/', 'src/index.css', 'src/types/api-gen.ts']

// 内联 style 里 CSS 常量属性关键字(命中即视为硬编码,除非 value 是变量/表达式/模板字符串)
const STYLE_CSS_KEYS = [
  'color',
  'backgroundColor',
  'background',
  'fontSize',
  'fontFamily',
  'fontWeight',
  'lineHeight',
  'letterSpacing',
  'padding',
  'paddingLeft',
  'paddingRight',
  'paddingTop',
  'paddingBottom',
  'margin',
  'marginLeft',
  'marginRight',
  'marginTop',
  'marginBottom',
  'border',
  'borderColor',
  'borderWidth',
  'borderRadius',
  'boxShadow',
]

// Tailwind arbitrary-value 工具类前缀白名单
const TAILWIND_ARBITRARY_PREFIXES = [
  'text',
  'rounded',
  'p',
  'px',
  'py',
  'pt',
  'pb',
  'pl',
  'pr',
  'm',
  'mx',
  'my',
  'mt',
  'mb',
  'ml',
  'mr',
  'w',
  'h',
  'max-w',
  'max-h',
  'min-w',
  'min-h',
  'top',
  'left',
  'right',
  'bottom',
  'gap',
  'leading',
  'tracking',
  'bg',
  'border',
  'inset',
  'shadow',
]

// ---------- Step 1: parse DESIGN.md YAML frontmatter，抽 token 白名单 ----------

function loadTokenAllowlist() {
  const raw = readFileSync(DESIGN_MD, 'utf8')
  // frontmatter：文件头 --- ... --- 之间
  const match = raw.match(/^---\r?\n([\s\S]*?)\r?\n---/)
  if (!match) throw new Error('DESIGN.md 缺 YAML frontmatter (--- ... ---)')
  const parsed = yamlLoad(match[1])

  const allowed = new Set()
  // colors → --color-<key>(@theme inline 注册) + --<key>(shadcn 原生变量名,:root 直接写)
  // shadcn 变量体系:colors key 直接用 shadcn 名(background/primary/accent 等),
  // :root 写 --background, @theme inline 注册 --color-background: var(--background)。
  // 两者都要在白名单,否则 E4 误报 var(--background) 未定义。
  for (const key of Object.keys(parsed.colors || {})) {
    allowed.add(`--color-${key}`)
    allowed.add(`--${key}`)
  }
  // typography → --font-<key>（字体族三件套） + --text-<key>（字号 scale）
  for (const key of Object.keys(parsed.typography || {})) {
    if (key.startsWith('font-')) {
      allowed.add(`--${key}`) // --font-display / --font-body / --font-mono
    } else {
      allowed.add(`--text-${key}`) // --text-display / --text-h1 / ...
    }
  }
  // rounded → --radius-<key> + --radius(shadcn 单值默认圆角,组件用)
  for (const key of Object.keys(parsed.rounded || {})) {
    allowed.add(`--radius-${key}`)
  }
  allowed.add(`--radius`)
  // spacing → --spacing-<key>
  for (const key of Object.keys(parsed.spacing || {})) {
    allowed.add(`--spacing-${key}`)
  }
  // shadow → --shadow-<key>
  for (const key of Object.keys(parsed.shadow || {})) {
    allowed.add(`--shadow-${key}`)
  }
  // motion → --motion-<key>
  for (const key of Object.keys(parsed.motion || {})) {
    allowed.add(`--motion-${key}`)
  }
  return allowed
}

// ---------- Step 2: 遍历 src 下要扫的文件 ----------

function walk(dir, acc = []) {
  for (const entry of readdirSync(dir)) {
    const abs = join(dir, entry)
    const st = statSync(abs)
    if (st.isDirectory()) walk(abs, acc)
    else acc.push(abs)
  }
  return acc
}

function isExempt(relPath) {
  return EXEMPT_PREFIXES.some((prefix) => relPath === prefix || relPath.startsWith(prefix))
}

function isScannable(relPath) {
  return /\.(ts|tsx|css)$/.test(relPath)
}

// ---------- Step 3: 单行扫描规则 ----------

/**
 * 判断某一行是否处于"注释状态"——极简判定,只覆盖单行注释和 CSS/JS 块注释起止在同一行的情况。
 * 跨行块注释暂不处理(会误报,可接受;真实需要时加行级 disable 注释)。
 */
function stripComments(line, ext) {
  if (ext === '.css') {
    // 去除 /* ... */ 块（同行内起止）
    return line.replace(/\/\*.*?\*\//g, '')
  }
  // ts/tsx：去除 // 单行注释后面部分 + /* ... */ 同行块
  let stripped = line.replace(/\/\*.*?\*\//g, '')
  const doubleSlashIdx = stripped.indexOf('//')
  if (doubleSlashIdx >= 0) {
    // 防止 URL 里的 // 误伤：只在无引号包裹时才截
    const before = stripped.slice(0, doubleSlashIdx)
    const quoteBalance =
      (before.match(/"/g) || []).length + (before.match(/'/g) || []).length + (before.match(/`/g) || []).length
    if (quoteBalance % 2 === 0) stripped = before
  }
  return stripped
}

/** 判定内联 style 的某 value 是否是"常量"（拦）；变量/表达式/模板串放行。 */
function isStyleValueConstant(rawValue) {
  const v = rawValue.trim()
  // 允许：反引号模板 `xxx${x}` / 三元 / 变量引用 / 函数调用（如 var()）
  if (v.startsWith('`') || v.startsWith('${')) return false
  if (/^\w+$/.test(v)) return false // 单一变量名
  if (v.includes('?') && v.includes(':')) return false // 三元
  if (/^var\s*\(/.test(v)) return false // var(--x) — 由 E4 分开处理
  // 允许：数字字面量（如 zIndex 数值、opacity 0.5、fontWeight 500）
  if (/^-?\d+(\.\d+)?$/.test(v)) return false
  // 剩余是字符串字面量 / 硬编码值 → 拦
  return /^['"]/.test(v) || /^-?\d+(\.\d+)?(px|rem|em|%|vh|vw)$/.test(v.replace(/^['"]|['"]$/g, ''))
}

function scanLine(line, ext, allowedVars) {
  const findings = []
  const stripped = stripComments(line, ext)

  // E1 硬编码 hex 颜色（排除 SVG fill/stroke attribute）
  const hexRegex = /#[0-9a-fA-F]{3,8}\b/g
  let m
  while ((m = hexRegex.exec(stripped)) !== null) {
    const before = stripped.slice(Math.max(0, m.index - 20), m.index)
    // 排除 SVG fill="#..." / stroke="#..."
    if (/(?:fill|stroke)\s*=\s*['"]\s*$/.test(before)) continue
    findings.push({
      col: m.index + 1,
      level: 'error',
      rule: 'E1',
      message: `硬编码 hex 颜色 "${m[0]}" — 用 DESIGN.md 定义的 --color-* 变量`,
    })
  }

  // E2 硬编码 rgb/hsl 颜色
  const funcColorRegex = /\b(?:rgb|rgba|hsl|hsla)\s*\(/g
  while ((m = funcColorRegex.exec(stripped)) !== null) {
    findings.push({
      col: m.index + 1,
      level: 'error',
      rule: 'E2',
      message: `硬编码 ${m[0].replace(/\s*\($/, '')}(...) 颜色 — 用 DESIGN.md 定义的 --color-* 变量`,
    })
  }

  // E3 内联 style={{ ...css-const... }} —— 仅对 .tsx/.ts 有意义
  if (ext === '.tsx' || ext === '.ts') {
    // 匹配 style={{ ... }} 段（不跨行，保守）
    const styleRegex = /style\s*=\s*\{\{([^}]*)\}\}/g
    while ((m = styleRegex.exec(stripped)) !== null) {
      const inner = m[1]
      // 分号或逗号分 property
      const pairs = inner.split(/[,;]/)
      for (const pair of pairs) {
        const kv = pair.split(':')
        if (kv.length < 2) continue
        const key = kv[0].trim().replace(/^['"]|['"]$/g, '')
        const value = kv.slice(1).join(':').trim()
        if (!STYLE_CSS_KEYS.includes(key)) continue
        if (isStyleValueConstant(value)) {
          findings.push({
            col: m.index + 1,
            level: 'error',
            rule: 'E3',
            message: `内联 style 常量 ${key}: ${value.slice(0, 40)}${value.length > 40 ? '...' : ''} — 用 Tailwind 类 (${
              key === 'fontSize'
                ? 'text-*'
                : key === 'borderRadius'
                  ? 'rounded-*'
                  : key === 'color'
                    ? 'text-*'
                    : key === 'backgroundColor'
                      ? 'bg-*'
                      : 'DESIGN.md token'
            }) 替代`,
          })
        }
      }
    }
  }

  // E4 var(--x) 引用未在 DESIGN.md 白名单
  const varRegex = /var\(\s*(--[a-z0-9-]+)/g
  while ((m = varRegex.exec(stripped)) !== null) {
    const name = m[1]
    if (!allowedVars.has(name)) {
      findings.push({
        col: m.index + 1,
        level: 'error',
        rule: 'E4',
        message: `var(${name}) 未在 DESIGN.md YAML token 定义 — 疑似 typo 或需先在 DESIGN.md 加`,
      })
    }
  }

  // W1 Tailwind arbitrary value（仅 .tsx/.ts 有意义）
  if (ext === '.tsx' || ext === '.ts') {
    // 匹配 xxx-[...] 且前缀在白名单里
    const arbRegex = /\b([a-z-]+)-\[([^\]]+)\]/g
    while ((m = arbRegex.exec(stripped)) !== null) {
      const prefix = m[1]
      if (TAILWIND_ARBITRARY_PREFIXES.includes(prefix)) {
        findings.push({
          col: m.index + 1,
          level: 'warn',
          rule: 'W1',
          message: `Tailwind arbitrary value "${m[0]}" — DESIGN.md scale 里应有对应 token，检查是否可换成 ${prefix}-* 命名类`,
        })
      }
    }
  }

  return findings
}

// ---------- Step 4: 主流程 ----------

function main() {
  const allowedVars = loadTokenAllowlist()
  const files = walk(SRC_DIR)
    .map((abs) => ({ abs, rel: relative(FRONTEND_ROOT, abs) }))
    .filter((f) => isScannable(f.rel) && !isExempt(f.rel))

  const allFindings = []
  for (const { abs, rel } of files) {
    const ext = abs.slice(abs.lastIndexOf('.'))
    const lines = readFileSync(abs, 'utf8').split(/\r?\n/)
    for (let i = 0; i < lines.length; i++) {
      const items = scanLine(lines[i], ext, allowedVars)
      for (const item of items) {
        allFindings.push({ file: rel, line: i + 1, ...item })
      }
    }
  }

  let errCount = 0
  let warnCount = 0
  for (const f of allFindings) {
    if (f.level === 'error') errCount++
    else warnCount++
    console.log(`${f.file}:${f.line}:${f.col}  ${f.level}  ${f.rule}  ${f.message}`)
  }

  console.log('')
  console.log(`Summary: ${errCount} error${errCount === 1 ? '' : 's'}, ${warnCount} warning${warnCount === 1 ? '' : 's'}`)
  console.log(`扫描 ${files.length} 文件 · Token 白名单 ${allowedVars.size} 项 (来源 DESIGN.md)`)

  process.exit(errCount > 0 ? 1 : 0)
}

main()
