import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Sheet, SheetTrigger, SheetContent, SheetTitle } from './sheet'

describe('Sheet', () => {
  it('trigger 打开抽屉显示内容', async () => {
    render(
      <Sheet>
        <SheetTrigger>开</SheetTrigger>
        <SheetContent>
          <SheetTitle>抽屉</SheetTitle>
        </SheetContent>
      </Sheet>,
    )
    expect(screen.queryByText('抽屉')).not.toBeInTheDocument()
    await userEvent.click(screen.getByText('开'))
    expect(screen.getByText('抽屉')).toBeInTheDocument()
  })

  it('side=right 时 content 有右侧定位类(inset-y-0 + right-0)', async () => {
    render(
      <Sheet>
        <SheetTrigger>开</SheetTrigger>
        <SheetContent side="right">
          <SheetTitle>抽屉</SheetTitle>
        </SheetContent>
      </Sheet>,
    )
    await userEvent.click(screen.getByText('开'))
    const content = screen.getByText('抽屉').closest('[data-slot="sheet-content"]')!
    expect(content).toHaveClass('right-0')
    expect(content).toHaveClass('inset-y-0')
  })
})
