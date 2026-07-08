---
version: alpha
name: KwikQuant
description: 加密量化交易工作台前端视觉契约。派生自 Coinbase 设计语言(institutional calm + Coinbase Blue + pill geometry + 暗段穿插 hero),colors key 对齐 shadcn 变量体系(零映射层),系统字体栈。token 直接映射 --background/--primary/--accent 等原生变量名。

colors:
  background: "#ffffff"
  foreground: "#0a0b0d"
  card: "#ffffff"
  card-foreground: "#0a0b0d"
  popover: "#ffffff"
  popover-foreground: "#0a0b0d"
  primary: "#0052ff"
  primary-foreground: "#ffffff"
  primary-active: "#003ecc"
  primary-disabled: "#a8b8cc"
  secondary: "#eef0e3"
  secondary-foreground: "#0a0b0d"
  muted: "#f7f7f7"
  muted-foreground: "#5b616e"
  accent: "#eef0e3"
  accent-foreground: "#0a0b0d"
  destructive: "#F63969"
  destructive-foreground: "#ffffff"
  border: "#dee1e6"
  input: "#ffffff"
  ring: "#0052ff"
  surface-dark: "#0a0b0d"
  surface-dark-elevated: "#16181c"
  on-dark: "#ffffff"
  on-dark-soft: "#a8acb3"
  semantic-up: "#2BA298"
  semantic-down: "#F63969"
  semantic-warning: "#f4b000"

typography:
  font-display: "-apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, 'Helvetica Neue', Arial, sans-serif"
  font-body: "-apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, 'Helvetica Neue', Arial, sans-serif"
  font-mono: "ui-monospace, 'SF Mono', 'Cascadia Code', 'Roboto Mono', Menlo, Consolas, 'Liberation Mono', monospace"
  display-mega:
    fontFamily: "{typography.font-display}"
    fontSize: 80px
    fontWeight: 400
    lineHeight: 1.0
    letterSpacing: -2px
  display-xl:
    fontFamily: "{typography.font-display}"
    fontSize: 64px
    fontWeight: 400
    lineHeight: 1.0
    letterSpacing: -1.6px
  display-lg:
    fontFamily: "{typography.font-display}"
    fontSize: 52px
    fontWeight: 400
    lineHeight: 1.0
    letterSpacing: -1.3px
  display-md:
    fontFamily: "{typography.font-display}"
    fontSize: 44px
    fontWeight: 400
    lineHeight: 1.09
    letterSpacing: -1px
  display-sm:
    fontFamily: "{typography.font-body}"
    fontSize: 36px
    fontWeight: 400
    lineHeight: 1.11
    letterSpacing: -0.5px
  title-lg:
    fontFamily: "{typography.font-body}"
    fontSize: 32px
    fontWeight: 400
    lineHeight: 1.13
    letterSpacing: -0.4px
  title-md:
    fontFamily: "{typography.font-body}"
    fontSize: 18px
    fontWeight: 600
    lineHeight: 1.33
    letterSpacing: 0
  title-sm:
    fontFamily: "{typography.font-body}"
    fontSize: 16px
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: 0
  body-md:
    fontFamily: "{typography.font-body}"
    fontSize: 16px
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0
  body-strong:
    fontFamily: "{typography.font-body}"
    fontSize: 16px
    fontWeight: 700
    lineHeight: 1.5
    letterSpacing: 0
  body-sm:
    fontFamily: "{typography.font-body}"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0
  caption:
    fontFamily: "{typography.font-body}"
    fontSize: 13px
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0
  caption-strong:
    fontFamily: "{typography.font-body}"
    fontSize: 12px
    fontWeight: 600
    lineHeight: 1.5
    letterSpacing: 0
  number-display:
    fontFamily: "{typography.font-mono}"
    fontSize: 18px
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: 0
  number-mono-sm:
    fontFamily: "{typography.font-mono}"
    fontSize: 13px
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: 0
  button:
    fontFamily: "{typography.font-body}"
    fontSize: 16px
    fontWeight: 600
    lineHeight: 1.15
    letterSpacing: 0
  nav-link:
    fontFamily: "{typography.font-body}"
    fontSize: 14px
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: 0

rounded:
  none: 0px
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 24px
  pill: 100px
  full: 9999px

spacing:
  xxs: 4px
  xs: 8px
  sm: 12px
  base: 16px
  md: 20px
  lg: 24px
  xl: 32px
  xxl: 48px
  section: 96px

components:
  top-nav-light:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.nav-link}"
    height: 64px
  top-nav-on-dark:
    backgroundColor: "{colors.surface-dark}"
    textColor: "{colors.on-dark}"
    typography: "{typography.nav-link}"
    height: 64px
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.primary-foreground}"
    typography: "{typography.button}"
    rounded: "{rounded.pill}"
    padding: 12px 20px
    height: 44px
  button-primary-active:
    backgroundColor: "{colors.primary-active}"
    textColor: "{colors.primary-foreground}"
    rounded: "{rounded.pill}"
  button-primary-disabled:
    backgroundColor: "{colors.primary-disabled}"
    textColor: "{colors.primary-foreground}"
    rounded: "{rounded.pill}"
  button-secondary-light:
    backgroundColor: "{colors.secondary}"
    textColor: "{colors.secondary-foreground}"
    typography: "{typography.button}"
    rounded: "{rounded.pill}"
    padding: 12px 20px
    height: 44px
  button-secondary-dark:
    backgroundColor: "{colors.surface-dark-elevated}"
    textColor: "{colors.on-dark}"
    typography: "{typography.button}"
    rounded: "{rounded.pill}"
    padding: 12px 20px
    height: 44px
  button-outline-on-dark:
    backgroundColor: transparent
    textColor: "{colors.on-dark}"
    typography: "{typography.button}"
    rounded: "{rounded.pill}"
    padding: 11px 19px
    height: 44px
  button-tertiary-text:
    backgroundColor: transparent
    textColor: "{colors.primary}"
    typography: "{typography.button}"
  button-pill-cta:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.primary-foreground}"
    typography: "{typography.button}"
    rounded: "{rounded.pill}"
    padding: 16px 32px
    height: 56px
  hero-band-dark:
    backgroundColor: "{colors.surface-dark}"
    textColor: "{colors.on-dark}"
    typography: "{typography.display-mega}"
    padding: 96px
  hero-band-light:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.display-mega}"
    padding: 96px
  product-ui-card-dark:
    backgroundColor: "{colors.surface-dark-elevated}"
    textColor: "{colors.on-dark}"
    rounded: "{rounded.xl}"
    padding: 32px
  product-ui-card-light:
    backgroundColor: "{colors.card}"
    textColor: "{colors.card-foreground}"
    rounded: "{rounded.xl}"
    padding: 32px
  feature-card:
    backgroundColor: "{colors.card}"
    textColor: "{colors.card-foreground}"
    typography: "{typography.title-md}"
    rounded: "{rounded.xl}"
    padding: 32px
  asset-row:
    backgroundColor: transparent
    textColor: "{colors.foreground}"
    typography: "{typography.body-md}"
    padding: 16px 0
  price-up-cell:
    backgroundColor: transparent
    textColor: "{colors.semantic-up}"
    typography: "{typography.number-display}"
  price-down-cell:
    backgroundColor: transparent
    textColor: "{colors.semantic-down}"
    typography: "{typography.number-display}"
  pricing-tier-card:
    backgroundColor: "{colors.card}"
    textColor: "{colors.card-foreground}"
    typography: "{typography.body-md}"
    rounded: "{rounded.xl}"
    padding: 32px
  pricing-tier-featured:
    backgroundColor: "{colors.surface-dark}"
    textColor: "{colors.on-dark}"
    typography: "{typography.body-md}"
    rounded: "{rounded.xl}"
    padding: 32px
  cta-band-dark:
    backgroundColor: "{colors.surface-dark}"
    textColor: "{colors.on-dark}"
    typography: "{typography.display-lg}"
    padding: 96px
  text-input:
    backgroundColor: "{colors.input}"
    textColor: "{colors.foreground}"
    typography: "{typography.body-md}"
    rounded: "{rounded.md}"
    padding: 14px 16px
    height: 48px
  search-input-pill:
    backgroundColor: "{colors.secondary}"
    textColor: "{colors.secondary-foreground}"
    typography: "{typography.body-md}"
    rounded: "{rounded.pill}"
    padding: 12px 20px
    height: 44px
  badge-pill:
    backgroundColor: "{colors.secondary}"
    textColor: "{colors.secondary-foreground}"
    typography: "{typography.caption-strong}"
    rounded: "{rounded.pill}"
    padding: 4px 12px
  asset-icon-circular:
    backgroundColor: "{colors.secondary}"
    rounded: "{rounded.full}"
    size: 32px
  footer-light:
    backgroundColor: "{colors.background}"
    textColor: "{colors.muted-foreground}"
    typography: "{typography.body-sm}"
    padding: 64px 48px
  footer-link:
    backgroundColor: transparent
    textColor: "{colors.muted-foreground}"
    typography: "{typography.body-sm}"
  legal-band:
    backgroundColor: "{colors.background}"
    textColor: "{colors.muted-foreground}"
    typography: "{typography.caption}"
---

## Overview

KwikQuant 前端读起来像一个 institutional 金融品牌,只是恰好交易 crypto —— 营销面安静、白 canvas、editorial 间距、几乎单色。唯一的品牌电压是 **Coinbase Blue**(`{colors.primary}` — #0052ff),用得极少:每个主 CTA pill、品牌 wordmark、内联强调链接。除此一抹蓝,整个系统是白 canvas + ink + 软灰 elevation band + 深 near-black editorial canvas(`{colors.surface-dark}` — #0a0b0d)用于全屏暗 hero。

Type 用系统无衬线栈,display 与 body 同族,靠 size + tracking + line-height 区分。**display 字重锁 400** —— 不是交易终端常见的 700+。这个选择传递 editorial calm 和 institutional trust,而非 fintech urgency。

页面节奏轮转三态:亮白 editorial 段、软灰 elevation band、**全屏暗 editorial hero** 携带分层 product-UI mockup 卡片。暗 hero + 浮动 dashboard mockup 是最显著的组件模式。

**关键特征:**
- 单一品牌色:`{colors.primary}`(#0052ff Coinbase Blue)承担每个主 CTA、wordmark、内联品牌链接。用得极少。
- 字重克制 —— display weight 400,绝不 700+。
- Pill geometry:每个 CTA 是 `{rounded.pill}`(100px),每个资产 glyph 是 `{rounded.full}`,每张卡是 `{rounded.xl}`(24px)。无锐角。
- 全屏暗 hero + 浮动 product-UI 卡:`{component.hero-band-dark}` + 内联 `{component.product-ui-card-dark}` mockup 是品牌最强签名模式。
- 交易语义:`{colors.semantic-up}`(#2BA298)和 `{colors.semantic-down}`(#F63969)—— 只作文本色,绝不作背景填充(买卖 CTA 例外)。
- 96px 段落节奏 —— editorial pacing 慷慨。
- shadcn 变量体系:colors key 直接映射 `--background`/`--primary`/`--accent` 等原生变量名,不另起语义层。

## Colors

### shadcn 变量映射

colors 段 key 直接用 shadcn 原生变量名(`background`/`foreground`/`primary`/`accent`/`secondary`/`muted`/`destructive`/`border`/`input`/`ring`/`card`/`popover`)。index.css `:root` 写这些 key 的值,`@theme inline` 注册 `--color-<key>: var(--<key>)` 生成 `bg-background`/`text-foreground`/`bg-primary` 等工具类。**不另起 `--color-surface-*` 语义层** —— shadcn 组件 `bg-accent` 直接 = DESIGN.md `accent`,零翻译,零撞车。

### Brand
- **Coinbase Blue**(`{colors.primary}` — #0052ff):唯一品牌色。每个主 CTA pill、wordmark、内联品牌链接。
- **Coinbase Blue Active**(`{colors.primary-active}` — #003ecc):按下态深蓝。
- **Coinbase Blue Disabled**(`{colors.primary-disabled}` — #a8b8cc):禁用 CTA 褪蓝。
- **Accent Yellow**(`{colors.semantic-warning}` — #f4b000):小面积子品牌色,极谨慎用。不作动作色。

### Surface
- **Background/Canvas**(`{colors.background}` — #ffffff):默认页底。
- **Secondary**(`{colors.secondary}` — #eef0e3 = surface-strong):次级按钮底、搜索 pill 底、asset icon 底。
- **Muted**(`{colors.muted}` — #f7f7f7 = surface-soft):静默背景、交替带。
- **Surface Dark**(`{colors.surface-dark}` — #0a0b0d):深 near-black,全屏暗 hero/CTA band。
- **Surface Dark Elevated**(`{colors.surface-dark-elevated}` — #16181c):暗主题卡片、浮动 mockup 卡。

### shadcn accent 语义(关键澄清)
- **Accent**(`{colors.accent}` — #eef0e3 = surface-strong):shadcn 的 `accent` 语义是 ghost/outline button 的 **hover 灰背景**,不是品牌强调色。品牌强调色是 `primary`(Coinbase Blue)。`accent` 就是灰 hover —— 消除"shadcn `bg-accent` 是蓝还是铜"的旧歧义。

### Hairlines & Borders
- **Border**(`{colors.border}` — #dee1e6 = hairline):白面上的 1px 分隔。
- **Input**(`{colors.input}` — #ffffff):输入框底。
- **Ring**(`{colors.ring}` — #0052ff = primary):focus 环 = 品牌色。

### Text
- **Foreground/Ink**(`{colors.foreground}` — #0a0b0d):标题、主 nav、body 强调。
- **Muted Foreground/Body**(`{colors.muted-foreground}` — #5b616e):默认正文、副文本。更弱层级用 `text-muted-foreground/70` 或 `/60` opacity,不另起 `text-body`/`text-muted` 语义名。

### Trading Semantics(与品牌色分离,只作文本色)
- **Semantic Up**(`{colors.semantic-up}` — #2BA298):涨/盈利,文本色。买入 CTA 底色是唯一例外。
- **Semantic Down**(`{colors.semantic-down}` — #F63969 = destructive):跌/亏损,文本色。统一红,destructive 复用此值。

## Typography

### Font Family
系统无衬线栈(display + body 同族,靠 size/tracking 区分)+ 系统等宽栈(数字)。**全系统字体栈,不加载外部字体**,首屏快。

- `{typography.font-display}` 与 `{typography.font-body}` 同栈(-apple-system 系),display 靠大 size + 负 tracking + weight 400 区分。
- `{typography.font-mono}` 系统等宽栈。所有数字/金额用此 + `tnum`/`zero` font-feature。

### Hierarchy

| Token | Size | Weight | LH | Tracking | Use |
|---|---|---|---|---|---|
| `{typography.display-mega}` | 80px | 400 | 1.0 | -2px | 首页 hero h1 |
| `{typography.display-xl}` | 64px | 400 | 1.0 | -1.6px | 子 hero |
| `{typography.display-lg}` | 52px | 400 | 1.0 | -1.3px | 段落标题 |
| `{typography.display-md}` | 44px | 400 | 1.09 | -1px | CTA band 标题 |
| `{typography.display-sm}` | 36px | 400 | 1.11 | -0.5px | 子段标题 |
| `{typography.title-lg}` | 32px | 400 | 1.13 | -0.4px | 卡组标题 |
| `{typography.title-md}` | 18px | 600 | 1.33 | 0 | 组件标题、资产行主 |
| `{typography.title-sm}` | 16px | 600 | 1.25 | 0 | 列表 label |
| `{typography.body-md}` | 16px | 400 | 1.5 | 0 | 默认正文 |
| `{typography.body-strong}` | 16px | 700 | 1.5 | 0 | 强调正文 |
| `{typography.body-sm}` | 14px | 400 | 1.5 | 0 | 副正文 |
| `{typography.caption}` | 13px | 400 | 1.5 | 0 | 说明 |
| `{typography.caption-strong}` | 12px | 600 | 1.5 | 0 | badge label |
| `{typography.number-display}` | 18px | 500 | 1.4 | 0 | 资产价格、涨跌幅 — mono |
| `{typography.number-mono-sm}` | 13px | 500 | 1.4 | 0 | 订单簿、持仓表数字 — mono |
| `{typography.button}` | 16px | 600 | 1.15 | 0 | 标准 CTA pill |
| `{typography.nav-link}` | 14px | 500 | 1.4 | 0 | 顶导航 |

### Principles
- **Display 字重锁 400。** 最显著的排版选择 —— 传递"calm institutional brand"而非"trading-platform urgency"。
- **负 tracking 只在 display。** display 用 -1px 到 -2px;body 保持 0。
- **Mono 在每个数字。** 资产价格、涨跌幅、订单簿、持仓、P&L —— 任何 tabular 数字用 `{typography.font-mono}` + `tnum`/`zero` feature,列对齐、实时跳动不抖。

## Layout

### Spacing System
- **Base unit:** 4px。
- **Tokens:** `{spacing.xxs}` 4px · `{spacing.xs}` 8px · `{spacing.sm}` 12px · `{spacing.base}` 16px · `{spacing.md}` 20px · `{spacing.lg}` 24px · `{spacing.xl}` 32px · `{spacing.xxl}` 48px · `{spacing.section}` 96px。
- **Section padding:** `{spacing.section}`(96px)用于每个主要 editorial band。
- **Card internal padding:** `{spacing.xl}`(32px)用于 feature 卡和 product-UI mockup。

### Grid & Container
- **Max content width:** ~1200px 居中。Hero 全屏。
- **Editorial body:** 12 列网格。
- **Feature card grids:** 桌面 2-up(hero split)或 3-up(benefit grid)。
- **Footer:** 6 列链接列表(桌面)。

### Whitespace Philosophy
慷慨 editorial pacing —— 更接近 Bloomberg 或 Financial Times,而非交易 dashboard。96px 段间距;段内卡片 24px 间距。密度在登录墙后,不在 marketing。

## Elevation & Depth

| Level | Treatment | Use |
|---|---|---|
| Flat | 无阴影无边框 | 80% surface |
| Hairline border | 1px `{colors.border}` | feature 卡描边 |
| Soft drop | `0 4px 12px rgba(0, 0, 0, 0.04)` | 单层阴影 tier — hovered 卡 |
| Photographic | 全屏 product-UI mockup | hero 深度 |

### Decorative Depth
- **暗 hero 内分层 product-UI 卡** 是最显著的装饰模式 —— `{component.product-ui-card-dark}` 浮在更深的 base canvas 上,常有第二张小卡斜向重叠。
- **几何品牌插画** 承担装饰深度,替代阴影。

## Shapes

### Border Radius Scale

| Token | Value | Use |
|---|---|---|
| `{rounded.none}` | 0px | 保留(基本不用) |
| `{rounded.xs}` | 4px | 内联 tag |
| `{rounded.sm}` | 8px | 紧凑行 |
| `{rounded.md}` | 12px | 表单输入 |
| `{rounded.lg}` | 16px | 中型卡 |
| `{rounded.xl}` | 24px | feature 卡、product-UI mockup、pricing tier |
| `{rounded.pill}` | 100px | 所有 CTA 按钮、搜索 pill、badge |
| `{rounded.full}` | 9999px | 资产 icon 圆、avatar |

Pill 用于交互,card-radius(24px)用于容器,full circle 用于 icon。无锐角。

## Components

### Top Navigation
**`top-nav-light`** — 白页默认顶 nav。底 `{colors.background}`,字 `{colors.foreground}`,高 64px。布局:品牌 wordmark 左,主菜单横排,search + Sign In + Sign Up CTA 右。

**`top-nav-on-dark`** — 暗 hero 段上顶 nav。底 `{colors.surface-dark}`,字 `{colors.on-dark}`。同布局。

### Buttons
**`button-primary`** — 签名 Coinbase Blue pill。底 `{colors.primary}`,字 `{colors.primary-foreground}`,type `{typography.button}`(16px/600),padding 12×20,height 44px,`{rounded.pill}`。

**`button-primary-active`** — 按下态。底 `{colors.primary-active}` 深蓝。

**`button-primary-disabled`** — 褪蓝。底 `{colors.primary-disabled}`。cursor not-allowed。

**`button-secondary-light`** — 白面软灰次级。底 `{colors.secondary}`,字 `{colors.secondary-foreground}`,同 pill。

**`button-secondary-dark`** — 暗 hero 用。底 `{colors.surface-dark-elevated}`,字 `{colors.on-dark}`。

**`button-outline-on-dark`** — 透明 pill 白描边。transparent,字 `{colors.on-dark}`,1px 白边。

**`button-tertiary-text`** — 内联文本链接。transparent,字 `{colors.primary}`。

**`button-pill-cta`** — 首页 hero 大 pill。同蓝调,56px height,16×32 padding。

### Hero Bands
**`hero-band-dark`** — 签名全屏暗 hero。底 `{colors.surface-dark}`,字 `{colors.on-dark}`,分层 product-UI mockup 卡。display 标题左 `{typography.display-mega}`(80px/400),副 `{typography.body-md}`,双 CTA。

**`hero-band-light`** — 白 canvas 变体。底 `{colors.background}`,字 `{colors.foreground}`。

### Cards
**`product-ui-card-dark`** — 浮动 product-UI mockup。底 `{colors.surface-dark-elevated}`,字 `{colors.on-dark}`,`{rounded.xl}`,padding 32px。常 2-3 张斜向堆叠。

**`product-ui-card-light`** — 亮变体。底 `{colors.card}`,1px hairline 边。

**`feature-card`** — 3-up/2-up 网格用。底 `{colors.card}`,type `{typography.title-md}`,`{rounded.xl}`,padding 32px。

### Pricing
**`pricing-tier-card`** — 标准 tier。底 `{colors.card}`,`{rounded.xl}`,padding 32px,1px hairline。

**`pricing-tier-featured`** — featured tier。底 `{colors.surface-dark}`,字 `{colors.on-dark}`。暗反转 = "highlighted choice",不用彩色 ribbon。

### Forms
**`text-input`** — 标准输入。底 `{colors.input}`,`{rounded.md}`,padding 14×16,height 48px,1px hairline。focus 时 border 加粗到 2px Coinbase Blue(`{colors.ring}`)。

**`search-input-pill`** — pill 搜索。底 `{colors.secondary}`,`{rounded.pill}`,height 44px。

### Tags & Badges
**`badge-pill`** — 小写 pill,段落 label("INSTITUTIONAL"、"REGULATED")。底 `{colors.secondary}`,`{typography.caption-strong}`,`{rounded.pill}`。

### CTA / Footer
**`cta-band-dark`** — pre-footer band。底 `{colors.surface-dark}`,字 `{colors.on-dark}`,垂直 padding 96px。

**`footer-light`** — 白 canvas footer。底 `{colors.background}`,字 `{colors.muted-foreground}`,6 列链接。

**`legal-band`** — footer 下法律条。字 `{colors.muted-foreground}`,`{typography.caption}`。

## Do's and Don'ts

### Do
- `{colors.primary}`(Coinbase Blue)只留给主 CTA、wordmark、品牌插画、内联强调链接。
- 每个 CTA 用 `{rounded.pill}`(100px);每个资产 glyph 用 `{rounded.full}`。
- Display 字重锁 400。
- 用暗/亮段轮转作页面节奏。
- 每个数字用 `{typography.font-mono}` + `tnum`/`zero` feature。
- 每个暗 hero 配分层 product-UI mockup 卡栈。
- token 直接用 shadcn 变量名(`bg-background`/`bg-primary`/`bg-accent`),不另起 `--color-surface-*` 语义层。

### Don't
- 不引入第二品牌色。Coinbase Blue 是唯一动作色;交易绿/红是 semantic-only(买卖 CTA 例外)。
- 不给 display 加粗 —— display 锁 400,加粗改变品牌声音。
- 不加多层 drop shadow —— 系统只有单层 soft-drop tier。
- 不在 CTA 上用 `{rounded.none}`(0px)锐角。
- 不混用 display 和 body 字体族在同一标题里。
- 不用交易绿/红作按钮背景(买卖 CTA `order-form-cta-buy/sell` 是唯一例外)。
- 不从第三方 widget(cookie consent/OneTrust)提取 CTA 色。
- 不另起 `--color-surface-canvas`/`--color-accent-copper` 语义层再映射 shadcn —— 少一层映射少一处撞车。

## Responsive Behavior

### Breakpoints
| Name | Width | Key Changes |
|---|---|---|
| Mobile | <640px | hero h1 80→40px;feature 卡 1-up;asset row 堆叠;nav 折叠 hamburger;分层 product-UI 卡折叠单卡。 |
| Tablet | 640–1024px | hero h1 64px;feature 卡 2-up;asset row 横向压缩。 |
| Desktop | 1024–1280px | 全 hero h1 80px;feature 卡 3-up;全 asset row 布局。 |
| Wide | >1280px | 内容 1200px 居中;hero 全屏。 |

### Touch Targets
- 主 CTA pill 44px height — WCAG AAA。
- hero 大 pill `{component.button-pill-cta}` 56px — 超 AAA。
- asset icon 圆 32px — 边界;8px row padding 创造有效 48px tap zone。

### Collapsing Strategy
- 顶 nav <768px 折 hamburger sheet。Sign Up CTA 保持可见。
- hero h1 阶降:80→64→52→44→36px。
- 分层 product-UI mockup 卡从 2-3 张堆叠折叠为移动端单卡。
- pricing tier:3-up→2-up→1-up。
- asset row 移动端纵向堆叠:ticker 上,price + change 下。

## Iteration Guide

1. 一次聚焦一个组件。直接引用 YAML key。
2. 新 CTA 默认 `{rounded.pill}`(100px);新 icon plate 默认 `{rounded.full}`;卡用 `{rounded.xl}`。
3. 变体作为 `components:` 块内独立条目。
4. 到处用 `{token.refs}` —— 不内联 hex。
5. Hover 态未文档化,只 Default + Active/Pressed。
6. display 400,body 400/600/700,mono 在每个数字。
7. Coinbase Blue 用得少 —— 每段一两个蓝时刻。

## Known Gaps

- **字体替代**:CoinbaseDisplay/Sans/Mono 是 licensed 专有字体;本系统用系统无衬线栈 + 系统等宽栈替代,损失部分字形特色,但首屏快、零字体加载。
- **交易面未覆盖**:coinbase 模板只覆盖营销面,交易面(order book / charts / order forms / position / P&L)在登录墙后,本文件不含其 token —— 遇到时按 Iteration Guide 在 `components:` 块增补。
- **动画时序**:out of scope,本文件不定义 motion token;`prefers-reduced-motion` 兜底在 index.css 用 inline 值。
- **focus 态可见性**:text-input focus 加粗到 2px ring,其他组件 focus 环统一 `{colors.ring}`(Coinbase Blue)2px outline-offset:2px。
- **coinbase 模板来源**:派生自 getdesign.md 的 coinbase 分析(社区从公开 CSS 提取的近似版,非 Coinbase 官方设计系统),色值为近似。
