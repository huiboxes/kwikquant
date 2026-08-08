export type Format = 'table' | 'json'

/** 输出:json 直 dump,table 调 renderTable。 */
export function output<T>(data: T, format: Format, renderTable: (d: T) => string): void {
  if (format === 'json') {
    process.stdout.write(JSON.stringify(data, null, 2) + '\n')
    return
  }
  process.stdout.write(renderTable(data) + '\n')
}

/** 简单列对齐表格(零依赖,不引 cli-table3)。 */
export function table(headers: string[], rows: (string | number)[][]): string {
  const widths = headers.map((h, i) => {
    const maxCell = rows.length > 0
      ? Math.max(...rows.map((r) => String(r[i] ?? '').length))
      : 0
    return Math.max(h.length, maxCell)
  })
  const pad = (cells: (string | number)[]) =>
    cells.map((c, i) => String(c ?? '').padEnd(widths[i])).join('  ')
  const sep = widths.map((w) => '-'.repeat(w)).join('  ')
  return [pad(headers), sep, ...rows.map(pad)].join('\n')
}
