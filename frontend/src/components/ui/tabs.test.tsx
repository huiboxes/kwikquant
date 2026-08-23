import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Tabs, TabsList, TabsTrigger, TabsContent } from './tabs'

describe('Tabs', () => {
  it('active 标签走 interactive-selected 平底(契约 Tabs)', () => {
    render(
      <Tabs defaultValue="a">
        <TabsList>
          <TabsTrigger value="a">甲</TabsTrigger>
          <TabsTrigger value="b">乙</TabsTrigger>
        </TabsList>
        <TabsContent value="a">内容甲</TabsContent>
      </Tabs>,
    )
    const active = screen.getByRole('tab', { name: '甲' })
    expect(active.className).toContain('data-[state=active]:bg-interactive-selected')
    expect(active.className).not.toContain('shadow-card')
  })

  it('focus 环 2px(契约 a11y)', () => {
    render(
      <Tabs defaultValue="a">
        <TabsList>
          <TabsTrigger value="a">甲</TabsTrigger>
        </TabsList>
      </Tabs>,
    )
    const tab = screen.getByRole('tab', { name: '甲' })
    expect(tab.className).toContain('focus-visible:ring-2')
  })
})
