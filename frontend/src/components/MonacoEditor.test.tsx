import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

// mock @monaco-editor/react Editor,测 mount/unmount/onChange(不加载真实 monaco)
vi.mock('@monaco-editor/react', () => ({
  default: ({ value, onChange, options }: {
    value: string
    onChange?: (v: string) => void
    options?: { readOnly?: boolean }
  }) => (
    <textarea
      data-testid="monaco-mock"
      value={value}
      readOnly={options?.readOnly}
      onChange={(e) => onChange?.(e.target.value)}
    />
  ),
  __esModule: true,
}))

import { MonacoEditor } from './MonacoEditor'

describe('MonacoEditor', () => {
  it('渲染 value', () => {
    render(<MonacoEditor value="# hello" />)
    const ta = screen.getByTestId('monaco-mock') as HTMLTextAreaElement
    expect(ta.value).toBe('# hello')
  })

  it('onChange 触发回调', () => {
    const onChange = vi.fn()
    render(<MonacoEditor value="" onChange={onChange} />)
    const ta = screen.getByTestId('monaco-mock') as HTMLTextAreaElement
    fireEvent.change(ta, { target: { value: 'new code' } })
    expect(onChange).toHaveBeenCalledWith('new code')
  })

  it('readOnly=true 时 textarea 只读', () => {
    render(<MonacoEditor value="x" readOnly />)
    const ta = screen.getByTestId('monaco-mock') as HTMLTextAreaElement
    expect(ta.readOnly).toBe(true)
  })
})
