#!/usr/bin/env node
/**
 * lint:ws — 对照 frontend/src/types/ws.ts 与 docs/ws-contract.md schema 字段名一致性。
 *
 * 防 ws.ts 类型与 ws-contract.md 契约文档漂移(曾发生 topic 单数/symbol- 漂移,
 * 见 memory project_market_ws_realtime)。新增 WS 事件类型时,字段名必须文档/代码对齐,
 * 否则前端订阅后字段 undefined 静默失败。
 *
 * 映射(ws.ts interface ↔ ws-contract.md §3.X schema 字段表第一列):
 *   WsTicker      ↔ §3.1 TickerEvent
 *   WsKline       ↔ §3.2 KlineEvent
 *   WsLiquidation ↔ §3.9 LiquidationEvent
 *
 * exit 0 = 字段名全对齐; exit 1 = 有漂移(缺字段/多字段/字段名不一致)。
 *
 * 限制:只校验字段名存在性,不校验类型(类型对照留人工,类型漂移 lint:design:usage + tsc 兜底)。
 */
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const WS_TS = resolve(ROOT, 'frontend/src/types/ws.ts')
const WS_CONTRACT = resolve(ROOT, 'docs/ws-contract.md')

// full=true: 双向校验(ws.ts ↔ md 字段集必须一致)。适用于全字段 schema(WsKline/WsLiquidation)。
// full=false: 单向校验(只抓 md 字段 ⊆ ws.ts,即文档写了但 ws.ts 没有的真漂移;ws.ts 多于文档 OK)。
// 适用于"常用子集,完整字段以代码为准"的 schema(§3.1 TickEvent 故意子集,避免二次漂移)。
const MAPPINGS = [
  { interface: 'WsTicker', section: '3.1', schema: 'TickEvent', full: false },
  { interface: 'WsKline', section: '3.2', schema: 'KlineEvent', full: true },
  { interface: 'WsLiquidation', section: '3.9', schema: 'LiquidationEvent', full: true },
]

function extractTsFields(content, interfaceName) {
  const re = new RegExp(`export interface ${interfaceName}\\s*\\{([\\s\\S]*?)\\n\\}`)
  const m = content.match(re)
  if (!m) return null
  const fields = []
  for (const line of m[1].split('\n')) {
    const stripped = line.replace(/\/\*[\s\S]*?\*\//g, '').trim()
    const m2 = stripped.match(/^(\w+)\s*:/)
    if (m2) fields.push(m2[1])
  }
  return fields
}

function extractMdFields(content, section) {
  const re = new RegExp(`### ${section}\\s+[\\s\\S]*?\\|\\s*字段\\s*\\|[\\s\\S]*?(?=\\n\\n|## )`)
  const m = content.match(re)
  if (!m) return null
  const fields = []
  for (const line of m[0].split('\n')) {
    const m2 = line.match(/^\|\s*(\w+)\s*\|/)
    if (m2 && m2[1] !== '字段' && !m2[1].startsWith('---')) fields.push(m2[1])
  }
  return fields
}

const wsTs = readFileSync(WS_TS, 'utf8')
const wsContract = readFileSync(WS_CONTRACT, 'utf8')

let errors = 0
for (const mapping of MAPPINGS) {
  const { interface: iface, section, schema, full } = mapping
  const tsFields = extractTsFields(wsTs, iface)
  const mdFields = extractMdFields(wsContract, section)
  if (tsFields === null) {
    console.error(`[ws] ${iface}: interface 未在 ws.ts 找到`)
    errors++
    continue
  }
  if (mdFields === null) {
    console.error(`[ws] §${section} ${schema}: 字段表未在 ws-contract.md 找到`)
    errors++
    continue
  }
  const tsSet = new Set(tsFields)
  const mdSet = new Set(mdFields)
  // 单向(始终校验):文档列了字段但 ws.ts 没有 = 真漂移
  const missingInTs = mdFields.filter((f) => !tsSet.has(f))
  if (missingInTs.length) {
    console.error(`[ws] ${iface} ↔ §${section} ${schema}: ws.ts 缺字段 ${JSON.stringify(missingInTs)}`)
    errors++
  }
  // 双向(仅 full=true):ws.ts 有但文档没列 = 全字段 schema 漂移(子集 schema 跳过)
  let missingInMd = []
  if (full) {
    missingInMd = tsFields.filter((f) => !mdSet.has(f))
    if (missingInMd.length) {
      console.error(`[ws] ${iface} ↔ §${section} ${schema}: ws-contract.md 缺字段 ${JSON.stringify(missingInMd)}`)
      errors++
    }
  }
  if (!missingInTs.length && !missingInMd.length) {
    const mode = full ? '双向' : '单向'
    console.log(`[ws] ${iface} ↔ §${section} ${schema}: ${mode} ${tsFields.length}/${mdFields.length} 字段对齐 ✓`)
  }
}

if (errors > 0) {
  console.error(`\n✗ ws-contract 漂移: ${errors} 处不齐`)
  process.exit(1)
}
console.log(`\n✓ ws-contract 对齐: ${MAPPINGS.length} 个 schema 全部字段名一致`)
