---
version: alpha
name: KwikQuant
description: 加密量化交易工作台前端视觉契约。暖橙品牌(#EB8131)+ 暗主默认暖黑画布 + serif display 签名;shadcn 原子为原语,token 走脚手架既有名(原生 + 语义层)。

colors:
  background: "#FAF8F4"
  foreground: "#1A1614"
  card: "#FFFFFF"
  card-foreground: "#1A1614"
  popover: "#FFFFFF"
  popover-foreground: "#1A1614"
  primary: "#EB8131"
  primary-foreground: "#FFFFFF"
  on-primary: "#FFFFFF"
  secondary: "#F3F0E9"
  secondary-foreground: "#1A1614"
  muted: "#F3F0E9"
  muted-foreground: "#5C544C"
  accent: "#EB8131"
  accent-foreground: "#FFFFFF"
  on-accent: "#FFFFFF"
  destructive: "#E60050"
  destructive-foreground: "#FFFFFF"
  border: "#E3DED2"
  input: "#F3F0E9"
  ring: "#EB8131"
  surface-canvas: "#FAF8F4"
  surface-card: "#FFFFFF"
  surface-card-2: "#F3F0E9"
  surface-input: "#F3F0E9"
  surface-hover: "#F3F0E9"
  text-primary: "#1A1614"
  text-secondary: "#5C544C"
  text-muted: "#8C8378"
  border-soft: "#EFEAE0"
  accent-soft: "#FCE6D5"
  accent-deep: "#C5651F"
  accent-warm: "#8C3D0E"
  onyx: "#14110F"
  slate: "#E8E4DA"
  surface-3: "#E8E4DA"
  accent-glow: "rgba(235,129,49,.35)"
  up: "#1E8E7E"
  down: "#E60050"
  warning: "#B8740A"
  warning-bg: "rgba(184,116,10,.12)"
  warning-text: "#B8740A"
  info: "#1E6FB8"
  interactive-hover: "#F3F0E9"
  interactive-active: "#E8E4DA"
  interactive-selected: "rgba(235,129,49,.10)"
  interactive-disabled: "#E8E4DA"
  # 合约语义别名(值复用,不引第二品牌色 —— 与 §Don't §引入第二品牌色一致)
  # long/short 用于 position-effect-button;liquidation 直接复用 warning 不另立别名(避免 lint unused)
  long: "{colors.up}"
  short: "{colors.down}"

typography:
  font-display: "Cormorant Garamond, Iowan Old Style, Apple Garamond, Baskerville, Georgia, Times New Roman, serif"
  font-body: "Inter, -apple-system, BlinkMacSystemFont, Segoe UI, system-ui, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
  font-mono: "ui-monospace, SF Mono, Menlo, JetBrains Mono, Cascadia Code, Roboto Mono, monospace"
  display:
    fontFamily: "{typography.font-display}"
    fontSize: 38px
    fontWeight: 400
    lineHeight: 1.05
    letterSpacing: -0.025em
  h1:
    fontFamily: "{typography.font-display}"
    fontSize: 30px
    fontWeight: 400
    lineHeight: 1.0
    letterSpacing: -0.02em
  h2:
    fontFamily: "{typography.font-display}"
    fontSize: 22px
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: -0.01em
  h3:
    fontFamily: "{typography.font-body}"
    fontSize: 17px
    fontWeight: 400
    lineHeight: 1.2
    letterSpacing: -0.01em
  body:
    fontFamily: "{typography.font-body}"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.6
    letterSpacing: 0
  body-sm:
    fontFamily: "{typography.font-body}"
    fontSize: 13px
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: 0
  caption:
    fontFamily: "{typography.font-body}"
    fontSize: 12px
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: 0
  label-caps:
    fontFamily: "{typography.font-body}"
    fontSize: 11px
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: 0.05em
  mono:
    fontFamily: "{typography.font-mono}"
    fontSize: 13px
    fontWeight: 500
    lineHeight: 1.4
    letterSpacing: 0

rounded:
  xs: 4px
  sm: 8px
  md: 10px
  lg: 12px
  xl: 16px
  2xl: 20px
  pill: 999px
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

shadow:
  card: "0 1px 2px rgba(20,17,15,.04), 0 6px 24px -12px rgba(20,17,15,.08)"
  pop: "0 12px 40px -16px rgba(20,17,15,.18)"

motion:
  fast: 120ms
  base: 200ms
  slow: 300ms

components:
  nav-active:
    backgroundColor: "{colors.interactive-selected}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.sm}"
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.primary-foreground}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.pill}"
    padding: 10px 18px
    height: 40px
  button-secondary:
    backgroundColor: "{colors.secondary}"
    textColor: "{colors.secondary-foreground}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.pill}"
    padding: 10px 18px
    height: 40px
  button-outline:
    backgroundColor: transparent
    textColor: "{colors.text-primary}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.pill}"
    padding: 10px 18px
    height: 40px
  button-ghost:
    backgroundColor: transparent
    textColor: "{colors.text-secondary}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.sm}"
    padding: 8px 12px
    height: 36px
  live-paper-badge:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.accent-warm}"
    typography: "{typography.label-caps}"
    rounded: "{rounded.pill}"
    padding: 3px 10px
  status-dot:
    backgroundColor: "{colors.up}"
    rounded: "{rounded.full}"
    size: 8px
  product-ui-card:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.text-primary}"
    typography: "{typography.body}"
    rounded: "{rounded.xl}"
    padding: 24px
  text-input:
    backgroundColor: "{colors.input}"
    textColor: "{colors.text-primary}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    padding: 10px 12px
    height: 40px
  badge-pill:
    backgroundColor: "{colors.secondary}"
    textColor: "{colors.text-secondary}"
    typography: "{typography.label-caps}"
    rounded: "{rounded.pill}"
    padding: 3px 10px
  # 合约专用:PERP 4 按钮(开多/开空/平多/平空)—— OKX 红绿双色,平仓态弱化
  # motion: 120ms hover/active 反馈(走 --motion-fast 全局基线)
  position-effect-button:
    typography: "{typography.body-sm}"
    rounded: "{rounded.md}"
    padding: 12px 8px
    borderColorActive: "{colors.long}" # 开多/平多
    borderColorActiveShort: "{colors.short}" # 开空/平空
    backgroundStrong: "{colors.long}" # 开仓态实色填充(字白)
    backgroundSoftLong: "rgba(30,142,126,.15)" # 平多弱化(字 long)
    backgroundSoftShort: "rgba(230,0,80,.15)" # 平空弱化(字 short)
    backgroundIdle: "{colors.surface-card-2}"
    textColorIdle: "{colors.text-secondary}"
    fontWeight: 700
    letterSpacing: 0.02em
    # 开仓态:实色填充 + 白字 + glow shadow
    # 平仓态:半透明 soft 底 + 对应 long/short 字色
    # 未选中:surface-card-2 底 + text-secondary 字 + 0.85 opacity
  # 合约专用:杠杆滑块(1-125x 范围,track 走品牌橙)
  # motion: 120ms 拖拽反馈(走 --motion-fast 全局基线)
  leverage-slider:
    trackHeight: 4px
    trackColor: "{colors.accent}"
    thumbSize: 16px
    thumbColor: "{colors.primary}"
    thumbRing: "2px solid {colors.surface-card}"
  # 合约专用:杠杆预设档位按钮(1/2/5/10/25/50/75/100/125)
  # motion: 120ms 反馈(走 --motion-fast 全局基线)
  leverage-preset:
    typography: "{typography.label-caps}"
    rounded: "{rounded.xs}"
    padding: 5px 2px
    borderColorIdle: "{colors.border}"
    borderColorActive: "{colors.primary}"
    backgroundIdle: "{colors.surface-card}"
    backgroundActive: "{colors.accent-soft}"
    textColorIdle: "{colors.text-secondary}"
    textColorActive: "{colors.primary}"
    fontWeight: 700
---

## Overview

KwikQuant 前端是一个暖 editorial 的量化交易工作台 —— 暖橙品牌色在暖黑画布上克制闪烁,serif display 签名给页面 editorial calm 的底色,mono 数字在跳动时不抖。视觉基调:暖、暗主、editorial pacing、serif display 签名。

**暗主默认**:`:root`=亮值、`:root.dark`=暗值;themeStore 启动给 `<html>` 挂 `.dark` class,暗值即默认渲染。亮主题作为备选皮肤保留(`ThemeToggle` 切换),只 surface/text/border 三组随主题变;品牌橙 `--primary`、暗实心 `--onyx`、交易语义 `--up`/`--down` 双主题共用。

**品牌主色**:`{colors.primary}`(#EB8131 暖橙)承担每个主 CTA、wordmark、focus 环、内联强调。用得克制 —— 每段一两个暖橙时刻。shadcn `accent` 语义复用此值作品牌色;shadcn ghost/outline 的 hover 灰背景走 `--surface-hover`,不撞 `--accent`。

**serif display 签名**:display 标题用 Cormorant Garamond serif 栈,字重锁 400,传递 editorial calm 而非 trading urgency。body 用 Inter sans-serif 栈;数字用 mono 系统等宽栈 + `tnum`/`zero` feature。

**用户操作叙事旅程**:Dashboard 是主入口,沿 **编码 → 回测 → 模拟 → 实盘** 旅程引导用户,零割裂全流程。不是后台管理系统的功能堆砌 —— 违反此原则即使视觉精致也会被否定。

**关键特征:**
- 单一品牌色:`{colors.primary}`(#EB8131)承担主 CTA、wordmark、focus 环、内联品牌链接。
- 暗主默认,暖黑画布(`#14110F`)+ 暖白卡(`#1B1714`)分层。
- serif display 签名(Cormorant Garamond,weight 400 锁死,绝不 700+)。
- Pill geometry:每个 CTA 是 `{rounded.pill}`(999px),每张卡是 `{rounded.xl}`(16px)。
- Mono 在每个数字:资产价格、涨跌幅、订单簿、持仓、P&L —— `tnum`/`zero` feature,列对齐、实时跳动不抖。
- token 名走脚手架既有:shadcn 原生(`background`/`primary`/`accent` 等)+ 语义层(`surface-*`/`text-*`/`accent-*`/`onyx`/`up`/`down`/...),零第三套名。

## Colors

### 双主题对照

| Token | 亮(`:root`)| 暗(`:root.dark`)|
|---|---|---|
| `background` / `surface-canvas` | #FAF8F4 | #14110F |
| `foreground` / `text-primary` | #1A1614 | #F5F2EC |
| `card` / `surface-card` | #FFFFFF | #1B1714 |
| `surface-card-2` / `secondary` / `muted` | #F3F0E9 | #241F1A |
| `surface-hover` / `interactive-hover` | #F3F0E9 | #241F1A |
| `text-secondary` / `muted-foreground` | #5C544C | #B8AFA2 |
| `text-muted` | #8C8378 | #7F766B |
| `border` | #E3DED2 | #2C2620 |
| `border-soft` | #EFEAE0 | #241F1A |
| `accent-soft` | #FCE6D5 | #3A2415 |
| `accent-warm` | #8C3D0E | #F5B98A |
| `slate` | #E8E4DA | #2C2620 |
| `up` | #1E8E7E | #2BA298 |
| `down` / `destructive` | #E60050 | #F63969 |
| `warning` / `warning-text` | #B8740A | #E0A043 |
| `info` | #1E6FB8 | #5BA8E8 |
| `interactive-active` | #E8E4DA | #2C2620 |
| `interactive-disabled` | #E8E4DA | #2C2620 |

### Brand
- **Primary**(`{colors.primary}` — #EB8131 暖橙):唯一品牌色,双主题共用。每个主 CTA pill、wordmark、focus 环、内联强调链接。
- **Accent Deep**(`{colors.accent-deep}` — #C5651F):品牌深变体,hover/active 强调用(双主题共用)。
- **Accent Soft**(`{colors.accent-soft}`):品牌软底,live-paper badge 底/品牌插画软底。亮 #FCE6D5,暗 #3A2415。
- **Accent Warm**(`{colors.accent-warm}`):品牌暖文字,在 accent-soft 底上作字色。亮 #8C3D0E(深暖棕),暗 #F5B98A(浅暖橙)。

### Surface
- **Canvas**(`{colors.surface-canvas}`):默认页底。亮 #FAF8F4 暖白,暗 #14110F 暖黑。
- **Card**(`{colors.surface-card}` / `{colors.card}`):卡片底。亮 #FFFFFF,暗 #1B1714。
- **Card-2**(`{colors.surface-card-2}` / `{colors.secondary}` / `{colors.muted}`):次级面/交替带/静默底。亮 #F3F0E9,暗 #241F1A。
- **Input**(`{colors.surface-input}` / `{colors.input}`):输入框底。
- **Hover**(`{colors.surface-hover}` / `{colors.interactive-hover}`):ghost/outline button hover 灰背景、行 hover 底。
- **Onyx**(`{colors.onyx}` — #14110F):暗实心,双主题共用。暗 hero/CTA band/wordmark 底。
- **Slate**(`{colors.slate}`):中性次级面,双主题共用值随主题变。亮 #E8E4DA,暗 #2C2620。

### Text
- **Primary**(`{colors.text-primary}` / `{colors.foreground}`):标题、主 nav、body 强调。
- **Secondary**(`{colors.text-secondary}` / `{colors.muted-foreground}`):默认正文、副文本。
- **Muted**(`{colors.text-muted}`):更弱层级 —— placeholder、disabled 文字、辅助说明。

### Border
- **Border**(`{colors.border}`):白/暗面上的 1px 分隔 hairline。
- **Border-Soft**(`{colors.border-soft}`):更软分隔,卡片内分组用。
- **Input**(`{colors.input}`):输入框底(= surface-card-2 值)。
- **Ring**(`{colors.ring}` — #EB8131 = primary):focus 环 = 品牌色。

### Trading Semantics(与品牌色分离)
- **Up**(`{colors.up}` / `{colors.long}` 别名):涨/盈利/做多,文本色。亮 #1E8E7E,暗 #2BA298。买入 CTA、开多 CTA 底色是唯一背景填充例外。
- **Down**(`{colors.down}` = `{colors.destructive}` / `{colors.short}` 别名):跌/亏损/做空,文本色 + 破坏操作底。亮 #E60050,暗 #F63969。统一红,destructive 复用此值。卖出 CTA、开空 CTA 底色同此规则。
- **Warning**(`{colors.warning}` / `{colors.warning-text}` / `{colors.warning-bg}`):警告/提示/强平价,文本色 + 软底。亮 #B8740A / `rgba(184,116,10,.12)`,暗 #E0A043 / `rgba(224,160,67,.14)`。
- **Info**(`{colors.info}`):信息/中性提示。亮 #1E6FB8,暗 #5BA8E8。

### Perp Contract Semantics(合约专用,值复用语义别名)
合约 UI 视觉走"双色对比 + 品牌橙驱动控件"。**不引第二品牌色** —— long/short 是语义别名,值直接复用 up/down;强平价直接复用 warning,不另立 liquidation 别名(避免 lint unused 污染)。

- **4 按钮(开多 / 开空 / 平多 / 平空)**(`{component.position-effect-button}`):OKX 风格红绿双色。开多/平多用 `{colors.long}` 绿,开空/平空用 `{colors.short}` 红。**开仓态实色填充 + 白字 + glow shadow(强对比)**;**平仓态弱化**:半透明 soft 底 + 对应 long/short 字色(无 glow)。未选中:surface-card-2 底 + text-secondary 字 + 0.85 opacity。
- **杠杆滑块**(`{component.leverage-slider}`):1-125x 范围,track 走 `{colors.primary}` 品牌橙,thumb 16px 品牌橙 + 2px surface-card ring。120ms 拖拽反馈(走 `--motion-fast` 全局基线)。
- **杠杆预设档位按钮**(`{component.leverage-preset}`):1/2/5/10/25/50/75/100/125 九档。未选中 surface-card 底 + text-secondary 字;选中 accent-soft 底 + primary 字 + brand border。
- **保证金模式 tab(逐仓 / 全仓)**:全仓 disabled + tooltip "开发中"。disabled 态走 `{colors.interactive-disabled}` 底 + `{colors.text-muted}` 字 + opacity 0.55。
- **底部信息行(强平价 / 保证金率 / 保证金占用)**:`{typography.font-mono}` + 弱化字色(`{colors.text-muted}` / `{colors.text-secondary}`),不抢主视觉。强平价走 `{colors.warning}` 文字色 + 700 weight;保证金率随档位变:`>80%` 走 down、`>50%` 走 warning、其余走 text-secondary。
- **持仓表合约列**:当持仓含 PERP 态时,补显示杠杆 / 保证金模式 / 标记价 / 强平价四列。SPOT 态在合约列显 "—"(text-muted)。强平价列用 `{colors.warning}` 字色 + 700 weight 提示风险。

### Interactive States
- **Hover**(`{colors.interactive-hover}` = `{colors.surface-hover}`):hover 灰背景。
- **Active**(`{colors.interactive-active}`):按下态深灰。
- **Selected**(`{colors.interactive-selected}`):选中态品牌橙低透明。亮 `rgba(235,129,49,.10)`,暗 `rgba(235,129,49,.12)`。
- **Disabled**(`{colors.interactive-disabled}`):禁用底色。

## Typography

### Font Family
三族系统字体栈,**不加载外部字体**(无 `@font-face`,无 webfont 请求)。font-family 栈声明 Inter/Cormorant Garamond 但系统回退 —— 首屏快,零字体加载,零 FOUT/FOIT。

- `{typography.font-display}` — Cormorant Garamond serif 栈(回退 Iowan Old Style / Apple Garamond / Baskerville / Georgia / Times New Roman)。display 标题用此,weight 400 锁死。
- `{typography.font-body}` — Inter sans-serif 栈(回退 -apple-system / BlinkMacSystemFont / Segoe UI / system-ui / PingFang SC / Hiragino Sans GB / Microsoft YaHei)。body 用此。
- `{typography.font-mono}` — 系统等宽栈(ui-monospace / SF Mono / Menlo / JetBrains Mono / Cascadia Code / Roboto Mono)。所有数字用此 + `tnum`/`zero` feature。

### Hierarchy

| Token | Size | Weight | LH | Tracking | Use |
|---|---|---|---|---|---|
| `{typography.display}` | 38px | 400 | 1.05 | -0.025em | 页面主 hero / 大标题 — serif |
| `{typography.h1}` | 30px | 400 | 1.0 | -0.02em | 段标题 — serif |
| `{typography.h2}` | 22px | 400 | 1.4 | -0.01em | 卡组标题 — serif |
| `{typography.h3}` | 17px | 400 | 1.2 | -0.01em | 组件标题 — sans |
| `{typography.body}` | 14px | 400 | 1.6 | 0 | 默认正文 |
| `{typography.body-sm}` | 13px | 400 | 1.4 | 0 | 紧凑正文、按钮 |
| `{typography.caption}` | 12px | 400 | 1.5 | 0 | 说明、副文本 |
| `{typography.label-caps}` | 11px | 600 | 1.4 | 0.05em | badge label、字段标签(caps) |
| `{typography.mono}` | 13px | 500 | 1.4 | 0 | 数字/金额 — mono |

### Principles
- **Display 字重锁 400。** 最显著的排版选择 —— 传递 editorial calm 而非 trading-platform urgency。绝不 700+。
- **负 tracking 只在 display。** display 用 -0.02em 到 -0.025em;body 保持 0。
- **Mono 在每个数字。** 资产价格、涨跌幅、订单簿、持仓、P&L —— 任何 tabular 数字用 `{typography.font-mono}` + `tnum`/`zero` feature,列对齐、实时跳动不抖。
- **不加载外部字体。** 栈声明 Inter/Cormorant Garamond 但无 `@font-face`,系统回退。首屏快;代价是跨机字形不一致(见 Known Gaps)。

## Layout

### Sidebar Rail
- **收起态** 64px 宽,只图标。
- **展开态** 248px 宽,图标 + label。
- collapsible,展开态 hover 不变,点击 toggle。
- 背景 `{colors.surface-card}`,右 1px `{colors.border}` hairline。
- nav-active 态:`{component.nav-active}`(底 `{colors.interactive-selected}`,字 `{colors.text-primary}`,圆角 `{rounded.sm}`)。

### TopBar
- height 60px,sticky top。
- 背景 `{colors.surface-card}`,底 1px `{colors.border}` hairline。
- 左:sidebar toggle + 面包屑;右:搜索 + 通知 + 主题 toggle + 用户菜单。

### Main
- max-width 1400px 居中。
- padding 24px(`{spacing.lg}`)。
- 背景 `{colors.surface-canvas}`。

### Spacing System
- **Base unit:** 4px。
- **Tokens:** `{spacing.xxs}` 4px · `{spacing.xs}` 8px · `{spacing.sm}` 12px · `{spacing.base}` 16px · `{spacing.md}` 20px · `{spacing.lg}` 24px · `{spacing.xl}` 32px · `{spacing.xxl}` 48px · `{spacing.section}` 96px。
- **Section padding:** `{spacing.section}`(96px)用于每个主要 editorial band。
- **Card internal padding:** 24px(`{spacing.lg}`)用于标准卡,32px(`{spacing.xl}`)用于 feature 卡。

### Whitespace Philosophy
慷慨 editorial pacing —— 96px 段间距;段内卡片 24px 间距。密度在数据密集表格和交易面,不在 marketing band。

## Elevation & Depth

| Level | Treatment | Use |
|---|---|---|
| Flat | 无阴影无边框 | 80% surface |
| Hairline border | 1px `{colors.border}` | 卡描边、行分隔 |
| Card | `{shadow.card}` | 标准卡片 |
| Pop | `{shadow.pop}` | popover/dialog/dropdown 浮层 |
| Card-hover | `{shadow.card}` + `translateY(-2px)` | hovered 卡 |

### Motion
- **Fast**(`{motion.fast}` 120ms):hover/toggle 微反馈。
- **Base**(`{motion.base}` 200ms):默认过渡。
- **Slow**(`{motion.slow}` 300ms):展开/收起、抽屉。
- `prefers-reduced-motion: reduce` 兜底:animation/transition duration 降到 0.01ms(index.css 全局基线)。

## Shapes

### Border Radius Scale

| Token | Value | Use |
|---|---|---|
| `{rounded.xs}` | 4px | 内联 tag、小 chip |
| `{rounded.sm}` | 8px | 紧凑行、nav-active |
| `{rounded.md}` | 10px | 表单输入、按钮(默认 `--radius`) |
| `{rounded.lg}` | 12px | 中型卡 |
| `{rounded.xl}` | 16px | 标准卡片、product-UI mockup |
| `{rounded.2xl}` | 20px | 大型 hero 卡 |
| `{rounded.pill}` | 999px | 所有 CTA 按钮、badge pill |
| `{rounded.full}` | 9999px | 资产 icon 圆、avatar、status-dot |

Pill 用于交互,card-radius(16px)用于容器,full circle 用于 icon。无锐角(`{rounded.none}` 不定义)。

## Components

### Card
shadcn `Card` 原子。底 `{colors.surface-card}`,字 `{colors.text-primary}`,1px `{colors.border}` hairline,`{rounded.xl}`,padding 24px(`{spacing.lg}`)。hovered 加 `{shadow.card}` + `translateY(-2px)`。

### Button
shadcn `Button` 原子,默认 `{rounded.pill}` height 40px。
- **primary**(`{component.button-primary}`):底 `{colors.primary}`,字 `{colors.primary-foreground}`,主 CTA。
- **secondary**(`{component.button-secondary}`):底 `{colors.secondary}`,次级 CTA。
- **outline**(`{component.button-outline}`):transparent + 1px `{colors.border}`,字 `{colors.text-primary}`。
- **ghost**(`{component.button-ghost}`):transparent,hover 底 `{colors.surface-hover}`。

### Dialog / Sheet
shadcn `Dialog` / `Sheet` 原子。浮层底 `{colors.surface-card}`,`{shadow.pop}`,`{rounded.xl}`。overlay 半透明 `{colors.onyx}` 50%。

### Tabs
shadcn `Tabs` 原子。active tab 底 `{colors.interactive-selected}` + 字 `{colors.text-primary}`;inactive 字 `{colors.text-secondary}`。

### Input
shadcn `Input` 原子。底 `{colors.input}`,字 `{colors.text-primary}`,`{rounded.md}`,height 40px,1px `{colors.border}` hairline。focus 时 border 加粗到 2px `{colors.ring}`(品牌橙)。

### Badge
shadcn `Badge` 原子。`{rounded.pill}`,`{typography.label-caps}`。outline/ghost 变体 hover 走 `{colors.surface-hover}`(已修复,不撞 `{colors.accent}`)。

### CommandDialog
shadcn `CommandDialog` 原子(⌘K 命令面板)。底 `{colors.surface-card}`,`{shadow.pop}`。item hover 走 `{colors.surface-hover}`,active 走 `{colors.interactive-selected}`。

### Sonner(Toast)
shadcn `Sonner` 原子。toast 底 `{colors.surface-card}`,字 `{colors.text-primary}`,`{rounded.sm}`,`{shadow.card}`。success 用 `{colors.up}` icon,error 用 `{colors.down}` icon。

### 自定义
- **nav-active**(`{component.nav-active}`):侧栏选中项。底 `{colors.interactive-selected}`,字 `{colors.text-primary}`,`{rounded.sm}`。
- **live-paper-badge**(`{component.live-paper-badge}`):PAPER 模拟盘标记。底 `{colors.accent-soft}`,字 `{colors.accent-warm}`,`{typography.label-caps}`,`{rounded.pill}`。与 LIVE 实盘必须视觉强区分(见 Do's and Don'ts)。
- **status-dot**(`{component.status-dot}`):连接状态点。底 `{colors.up}`(connected)/ `{colors.down}`(error),`{rounded.full}`,8px。

## Do's and Don'ts

### Do
- `{colors.primary}`(#EB8131)只留给主 CTA、wordmark、focus 环、内联强调链接。
- 每个 CTA 用 `{rounded.pill}`(999px);每个资产 glyph 用 `{rounded.full}`;每张卡用 `{rounded.xl}`(16px)。
- Display 字重锁 400(serif Cormorant Garamond)。
- 每个数字用 `{typography.font-mono}` + `tnum`/`zero` feature —— 金额一律 `decimal.js`(`src/lib/money.ts` 是唯一入口),`parseFloat`/`Number` 参与金额运算被 ESLint 硬拦。
- 涨跌不靠颜色单独表达:配 ↑↓ 箭头 + 文本标签(a11y WCAG 2.2 AA)。up/down 语义色与品牌色分离。
- 用暗/亮段轮转作页面节奏;暗主默认,亮为备选。
- token 名走脚手架既有:shadcn 原生(`bg-background`/`bg-primary`/`bg-accent`)+ 语义层(`bg-surface-canvas`/`text-text-primary`/`bg-accent-soft`)。
- **合约 4 按钮(开多/开空/平多/平空)走 `{component.position-effect-button}`**:开仓态实色填充 + 白字,平仓态弱化半透明 + 对应字色,未选中弱化态。
- **杠杆控件走品牌橙驱动**:`{component.leverage-slider}` + `{component.leverage-preset}` 用 `{colors.primary}`,不混用 up/down。
- **持仓表 PERP 态显合约列、SPOT 态显 —**:不删列,跨 marketType 列结构保持一致(对齐体验)。

### Don't
- 不引入第二品牌色。`{colors.primary}`(#EB8131)是唯一动作色;交易绿/红是 semantic-only(买卖 CTA `order-form-cta-buy/sell` 是唯一背景填充例外)。**合约 long/short 是语义别名,值复用 up/down,不引第三/第四套色;强平价直接复用 warning 不另立 liquidation 别名。**
- 不用纯黑 `#000` —— 用 `{colors.onyx}`(#14110F 暖黑)。
- 不给 display 加粗 —— display 锁 400,加粗改变品牌声音。
- 不加多层 drop shadow —— 系统只有 `{shadow.card}` + `{shadow.pop}` 两层 + card-hover translateY。
- 不在 CTA 上用锐角(`{rounded.none}` 不定义)。
- 不混用 display(serif)和 body(sans)字体族在同一标题里。
- 不用交易绿/红作按钮背景(买卖 CTA 是唯一例外)。
- 不引入第三套 token 名(`--canvas`/`--ink`/`--brand` 等)—— 脚手架既有名(shadcn 原生 + 语义层)是唯一 token 体系。
- **PAPER 模拟盘 vs 实盘必须视觉强区分**:用户绝不能误把实盘当模拟盘下单。用 live-paper badge 标记/颜色/确认弹窗多层防护。
- 不硬编码颜色/圆角/字号(`#000`/`#fff`/`24px` 等)—— token 走 `DESIGN.md` → `index.css` → 组件类。
- **不在合约 4 按钮上混用 positionEffect 枚举英文作主文案**:用户可见文案是中文(开多/开空/平多/平空)。枚举(OPEN_LONG 等)只作辅助小字标签,且字号 ≤ 9.5px。
- **不在杠杆滑块上用 up/down 色**:杠杆是控件而非方向,走品牌橙(与方向色分离)。

### a11y
- 正文对比度 ≥ 4.5:1,UI 边界 ≥ 3:1。
- 状态不单靠颜色(↑↓ 箭头 + 文本标签 + icon)。
- 全键盘 + 可见焦点(`{colors.ring}` 2px outline-offset 2px)。
- `prefers-reduced-motion` 兜底(index.css 全局基线)。

## Responsive Behavior

### Breakpoints
| Name | Width | Key Changes |
|---|---|---|
| Mobile | <640px | sidebar 收起 64px + hamburger sheet;feature 卡 1-up;数字表横向滚动。 |
| Tablet | 640–980px | sidebar 展开 248px;feature 卡 2-up。 |
| Desktop | 980–1280px | 全布局;feature 卡 3-up。 |
| Wide | >1280px | main max-width 1400px 居中。 |

### Collapsing Strategy
- sidebar <900px 折 hamburger sheet(`Sheet` 原子从左滑入)。toggle 按钮保持可见。
- topbar <980px 面包屑折叠为当前页名 + 返回。
- feature card grid:3-up → 2-up → 1-up。
- 数字表 <760px 横向滚动(首列 sticky),不堆叠(保持行对齐)。

### Touch Targets
- 主 CTA pill 40px height — WCAG AA。
- icon button 36px — 边界。
- nav item 40px height — 有效 tap zone。

## Iteration Guide

1. 一次聚焦一个组件。直接引用 YAML key,不内联 hex。
2. 新 CTA 默认 `{rounded.pill}`(999px);新 icon plate 默认 `{rounded.full}`;卡用 `{rounded.xl}`(16px)。
3. 变体作为 `components:` 块内独立条目。
4. 到处用 `{token.refs}` —— 不内联 hex。
5. Hover 态默认走 `{colors.surface-hover}`(ghost/outline)或 `{colors.interactive-hover}`(行);active 走 `{colors.interactive-active}`;selected 走 `{colors.interactive-selected}`。
6. display 400(serif),body 400/600,body-sm 400,mono 在每个数字(weight 500)。
7. `{colors.primary}`(#EB8131)用得少 —— 每段一两个暖橙时刻。

## Known Gaps

- **字体跨机一致性**:font-family 栈声明 Inter/Cormorant Garamond 但无 `@font-face`,系统回退。跨机字形不一致(Iowan Old Style / Georgia / PingFang SC 回退);代价是首屏快、零字体加载。后续如需品牌字形一致性,再评估加载 webfont。
- **type 派生**:九个 font-size scale 是手工定义的,未从 ratio 自动派生。调整 scale 需改 index.css 九处 + DESIGN.md 九处。
- **light 主题待验**:亮主题(`:root`)作为备选皮肤保留,实际使用以暗主为主。亮主题的对比度和视觉平衡待实际使用后微调。
