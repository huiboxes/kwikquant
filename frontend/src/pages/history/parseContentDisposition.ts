/**
 * parseContentDispositionFilename — 从 Content-Disposition header 解析文件名。
 *
 * 后端 export 响应 header 形如 `Content-Disposition: attachment; filename="trade-history.csv"`
 * 或无引号 `attachment; filename=trade-history.csv`。解析失败返 null,调用方用默认文件名。
 */
export function parseContentDispositionFilename(
  header: string | null,
): string | null {
  if (!header) return null
  const quoted = header.match(/filename="([^"]+)"/i)
  if (quoted?.[1]) return quoted[1]
  const unquoted = header.match(/filename=([^;]+)/i)
  return unquoted?.[1]?.trim() ?? null
}
