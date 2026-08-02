const BANNER_H = 56

/** PNG 导出元数据(顶部 banner 文字层,让离线看图知道是哪个回测)。 */
export interface PngExportMeta {
  strategyName: string
  symbol: string
  interval: string
  range: string
  totalReturn: string
  totalReturnTone: 'up' | 'down' | 'neutral'
}

/**
 * SVG→PNG 序列化下载(EquityCurveChart 渲染 SVG 无 canvas ref,通过 querySelector 拿 svg 元素后调此)。
 *
 * 流程:XMLSerializer 序列化 svg → Blob(svg) → Image 加载 → canvas.drawImage → toDataURL → <a download>。
 * 2x 缩放保清晰度。传 meta 时 canvas 顶部加 56px banner 文字层(策略名+身份行+总收益率),
 * 运行时 getComputedStyle 读 CSS 变量(亮暗自动),让离线 PNG 自带身份上下文(B3)。
 */
export async function downloadEquityPng(
  svgEl: SVGSVGElement,
  fileName: string,
  meta?: PngExportMeta,
): Promise<void> {
  const serializer = new XMLSerializer()
  const svgStr = serializer.serializeToString(svgEl)
  const svgBlob = new Blob([svgStr], { type: 'image/svg+xml;charset=utf-8' })
  const url = URL.createObjectURL(svgBlob)

  const img = new Image()
  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve()
    img.onerror = () => reject(new Error('SVG 加载失败'))
    img.src = url
  })

  const bannerH = meta ? BANNER_H : 0
  const canvas = document.createElement('canvas')
  const bbox = svgEl.getBoundingClientRect()
  const W = Math.max(1, Math.floor(bbox.width))
  canvas.width = W * 2 // 2x 清晰度
  canvas.height = (Math.max(1, Math.floor(bbox.height)) + bannerH) * 2
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('canvas 2d context 不可用')
  ctx.scale(2, 2)

  if (meta) {
    // 运行时读 CSS 变量(亮暗自动),fallback 兜底 token 缺失
    const css = getComputedStyle(document.documentElement)
    const surfaceCard = css.getPropertyValue('--surface-card').trim() || '#FFFFFF'
    const textPrimary = css.getPropertyValue('--text-primary').trim() || '#1A1614'
    const textMuted = css.getPropertyValue('--text-muted').trim() || '#8C8378'
    const borderSoft = css.getPropertyValue('--border-soft').trim() || '#EFEAE0'
    const up = css.getPropertyValue('--up').trim() || '#1E8E7E'
    const down = css.getPropertyValue('--down').trim() || '#E60050'
    const fontMono = css.getPropertyValue('--font-mono').trim() || 'ui-monospace, monospace'

    // banner 底 + 分隔线
    ctx.fillStyle = surfaceCard
    ctx.fillRect(0, 0, W, bannerH)
    ctx.fillStyle = borderSoft
    ctx.fillRect(0, bannerH - 1, W, 1)
    // 左:策略名(16px bold)+ 身份行(12px muted)
    ctx.fillStyle = textPrimary
    ctx.font = `600 16px ${fontMono}`
    ctx.textBaseline = 'middle'
    ctx.textAlign = 'left'
    ctx.fillText(meta.strategyName, 16, 18)
    ctx.fillStyle = textMuted
    ctx.font = `400 12px ${fontMono}`
    ctx.fillText(`${meta.symbol} · ${meta.interval} · ${meta.range}`, 16, 40)
    // 右:总收益率标签(10px muted)+ 值(20px bold 语义色)
    ctx.fillStyle = textMuted
    ctx.font = `400 10px ${fontMono}`
    ctx.textAlign = 'right'
    ctx.fillText('总收益率', W - 16, 12)
    const toneColor = meta.totalReturnTone === 'up' ? up : meta.totalReturnTone === 'down' ? down : textPrimary
    ctx.fillStyle = toneColor
    ctx.font = `700 20px ${fontMono}`
    ctx.fillText(meta.totalReturn, W - 16, 28)
  }

  ctx.drawImage(img, 0, bannerH)
  URL.revokeObjectURL(url)

  const pngUrl = canvas.toDataURL('image/png')
  const a = document.createElement('a')
  a.href = pngUrl
  a.download = fileName
  a.click()
}
