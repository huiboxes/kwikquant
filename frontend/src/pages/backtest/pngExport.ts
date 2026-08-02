/**
 * SVG→PNG 序列化下载(EquityCurveChart 渲染 SVG 无 canvas ref,通过 querySelector 拿 svg 元素后调此)。
 *
 * 流程:XMLSerializer 序列化 svg → Blob(svg) → Image 加载 → canvas.drawImage → toDataURL → <a download>。
 * 2x 缩放保清晰度。
 */
export async function downloadEquityPng(svgEl: SVGSVGElement, fileName: string): Promise<void> {
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

  const canvas = document.createElement('canvas')
  const bbox = svgEl.getBoundingClientRect()
  canvas.width = Math.max(1, Math.floor(bbox.width)) * 2 // 2x 清晰度
  canvas.height = Math.max(1, Math.floor(bbox.height)) * 2
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('canvas 2d context 不可用')
  ctx.scale(2, 2)
  ctx.drawImage(img, 0, 0)

  URL.revokeObjectURL(url)

  const pngUrl = canvas.toDataURL('image/png')
  const a = document.createElement('a')
  a.href = pngUrl
  a.download = fileName
  a.click()
}
