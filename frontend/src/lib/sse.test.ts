import { describe, it, expect } from 'vitest'
import { parseSseFrame, parseSseFrames } from './sse'

describe('parseSseFrame', () => {
  it('event:message + data 单行', () => {
    const frame = parseSseFrame('event: message\ndata: hello')
    expect(frame).toEqual({ event: 'message', data: 'hello' })
  })

  it('无 event 行默认 message', () => {
    const frame = parseSseFrame('data: chunk1')
    expect(frame?.event).toBe('message')
    expect(frame?.data).toBe('chunk1')
  })

  it('data 多行用 \\n 拼接(SSE 规范)', () => {
    const frame = parseSseFrame('event: message\ndata: line1\ndata: line2')
    expect(frame?.data).toBe('line1\nline2')
  })

  it('data: 后前导空格去掉', () => {
    const frame = parseSseFrame('data:   spaced')
    expect(frame?.data).toBe('  spaced') // 只去 1 个前导空格(SSE 规范)
  })

  it('event:error', () => {
    const frame = parseSseFrame('event: error\ndata: 余额不足')
    expect(frame).toEqual({ event: 'error', data: '余额不足' })
  })

  it('event:done + 空 data 返 null(无 data 行)', () => {
    const frame = parseSseFrame('event: done')
    expect(frame).toBeNull()
  })

  it('event:done + 有 data', () => {
    const frame = parseSseFrame('event: done\ndata: ')
    expect(frame?.event).toBe('done')
  })

  it('忽略 id/retry/注释行', () => {
    const frame = parseSseFrame(': comment\nevent: message\ndata: x\nid: 1\nretry: 1000')
    expect(frame).toEqual({ event: 'message', data: 'x' })
  })

  it('空 raw 返 null', () => {
    expect(parseSseFrame('')).toBeNull()
  })
})

describe('parseSseFrames', () => {
  it('多帧一次解析(\\n\\n 分隔)', () => {
    const buffer = 'event: message\ndata: a\n\nevent: message\ndata: b\n\n'
    const { frames, rest } = parseSseFrames(buffer)
    expect(frames).toHaveLength(2)
    expect(frames[0].data).toBe('a')
    expect(frames[1].data).toBe('b')
    expect(rest).toBe('')
  })

  it('不完整帧保留在 rest', () => {
    const buffer = 'event: message\ndata: a\n\nevent: message\ndata: b'
    const { frames, rest } = parseSseFrames(buffer)
    expect(frames).toHaveLength(1)
    expect(frames[0].data).toBe('a')
    expect(rest).toBe('event: message\ndata: b')
  })

  it('空 buffer 返空', () => {
    const { frames, rest } = parseSseFrames('')
    expect(frames).toHaveLength(0)
    expect(rest).toBe('')
  })

  it('增量喂:先半帧再补全', () => {
    let buffer = 'event: message\ndata: hel'
    let parsed = parseSseFrames(buffer)
    expect(parsed.frames).toHaveLength(0)
    expect(parsed.rest).toBe(buffer)
    buffer = parsed.rest + 'lo\n\n'
    parsed = parseSseFrames(buffer)
    expect(parsed.frames).toHaveLength(1)
    expect(parsed.frames[0].data).toBe('hello')
    expect(parsed.rest).toBe('')
  })

  it('混合 event:message / error / done', () => {
    const buffer = [
      'event: message\ndata: chunk1',
      'event: error\ndata: 报错',
      'event: done\ndata: ',
    ].join('\n\n') + '\n\n'
    const { frames } = parseSseFrames(buffer)
    expect(frames).toHaveLength(3)
    expect(frames[0]).toEqual({ event: 'message', data: 'chunk1' })
    expect(frames[1]).toEqual({ event: 'error', data: '报错' })
    expect(frames[2].event).toBe('done')
  })
})
