---
version: alpha
name: KwikQuant
description: 加密量化交易工作台前端视觉契约。冷白画布(#EEF0F2)+ 白卡无边界 + 粗 sans display;品牌橙(#EB8131)降级为仅主 CTA / focus 环 / 内联强调,不作装饰色;shadcn 原子为原语,token 走脚手架既有名(原生 + 语义层)。

colors:
  background: "#EEF0F2"
  foreground: "#11151C"
  card: "#FFFFFF"
  card-foreground: "#11151C"
  popover: "#FFFFFF"
  popover-foreground: "#11151C"
  primary: "#EB8131"
  primary-foreground: "#FFFFFF"
  on-primary: "#FFFFFF"
  secondary: "#F1F3F5"
  secondary-foreground: "#11151C"
  muted: "#F1F3F5"
  muted-foreground: "#5C6675"
  accent: "#EB8131"
  accent-foreground: "#FFFFFF"
  on-accent: "#FFFFFF"
  destructive: "#E60050"
  destructive-foreground: "#FFFFFF"
  border: "#E1E4E8"
  input: "#F1F3F5"
  ring: "#EB8131"
  surface-canvas: "#EEF0F2"
  surface-card: "#FFFFFF"
  surface-card-2: "#F1F3F5"
  surface-input: "#F1F3F5"
  surface-hover: "#E9EBEE"
  text-primary: "#11151C"
  text-secondary: "#5C6675"
  text-muted: "#8B94A3"
  border-soft: "#E9EBEE"
  accent-soft: "#FBE8D9"
  accent-deep: "#C5651F"
  accent-warm: "#8C3D0E"
  onyx: "#0C0F14"
  slate: "#E0E4E9"
  surface-3: "#E0E4E9"
  accent-glow: "rgba(235,129,49,.35)"
  up-glow: "rgba(30,142,126,.40)"
  down-glow: "rgba(230,0,80,.40)"
  up: "#1E8E7E"
  down: "#E60050"
  warning: "#B8740A"
  warning-bg: "rgba(184,116,10,.12)"
  warning-text: "#B8740A"
  info: "#1E6FB8"
  interactive-hover: "#E9EBEE"
  interactive-active: "#DCE0E6"
  interactive-selected: "#E2E6EB"
  interactive-disabled: "#E0E4E9"
  # 合约语义别名(值复用,不引第二品牌色 —— 与 §Don't §引入第二品牌色一致)
  # long/short 用于 position-effect-button;liquidation 直接复用 warning 不另立别名(避免 lint unused)
  long: "{colors.up}"
  short: "{colors.down}"

typography:
  font-body: "Inter, -apple-system, BlinkMacSystemFont, Segoe UI, system-ui, PingFang SC, Hiragino Sans GB, Microsoft YaHei, sans-serif"
  font-mono: "ui-monospace, SF Mono, Menlo, JetBrains Mono, Cascadia Code, Roboto Mono, monospace"
  display:
    fontFamily: "{typography.font-body}"
    fontSize: 38px
    fontWeight: 600
    lineHeight: 1.1
    letterSpacing: -0.025em
  h1:
    fontFamily: "{typography.font-body}"
    fontSize: 30px
    fontWeight: 600
    lineHeight: 1.15
    letterSpacing: -0.02em
  h2:
    fontFamily: "{typography.font-body}"
    fontSize: 22px
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: -0.01em
  h3:
    fontFamily: "{typography.font-body}"
    fontSize: 17px
    fontWeight: 600
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
  caption-sm:
    fontFamily: "{typography.font-body}"
    fontSize: 11px
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: 0
  caption-xs:
    fontFamily: "{typography.font-body}"
    fontSize: 10px
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: 0
  micro:
    fontFamily: "{typography.font-body}"
    fontSize: 9px
    fontWeight: 400
    lineHeight: 1.3
    letterSpacing: 0
  kpi-sm:
    fontFamily: "{typography.font-body}"
    fontSize: 16px
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: 0
  kpi:
    fontFamily: "{typography.font-body}"
    fontSize: 20px
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: 0
  metric:
    fontFamily: "{typography.font-body}"
    fontSize: 24px
    fontWeight: 600
    lineHeight: 1.1
    letterSpacing: 0
  stat:
    fontFamily: "{typography.font-body}"
    fontSize: 36px
    fontWeight: 700
    lineHeight: 1.1
    letterSpacing: -0.02em
  hero:
    fontFamily: "{typography.font-body}"
    fontSize: 60px
    fontWeight: 700
    lineHeight: 1.05
    letterSpacing: -0.025em

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
  # 控件微阴影:switch/slider 拇指专用;体系仍为 card/pop 两层 + 此微阴影
  control: "0 1px 2px rgba(20,17,15,.08)"

motion:
  fast: 120ms
  base: 200ms
  slow: 300ms
  # 主题切换:View Transitions 交叉过渡;不支持直切,reduced-motion 跳过
  theme-swap: 200ms
  # 内容切换档:tab 淡入 / 展开收起 / 列表行入场
  tab-content: 200ms
  expand: 300ms
  list-enter: 200ms

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
  # 合约专用:杠杆滑块(1-100x 范围,track/thumb 走中性色)
  # motion: 120ms 拖拽反馈(走 --motion-fast 全局基线)
  leverage-slider:
    trackHeight: 4px
    trackColor: "{colors.surface-3}"
    thumbSize: 16px
    thumbColor: "{colors.onyx}"
    thumbRing: "2px solid {colors.surface-card}"
  # 骨架屏:shimmer 有方向感("数据正在流入"),形态必须模仿终态;reduced-motion 回退静帧
  skeleton:
    animation: "shimmer 1.6s linear infinite"
    base: "{colors.surface-card-2}"
    highlight: "{colors.surface-3}"
  # 数字变化反馈:方向翻转背景闪 + 涨跌着色;金额比较走 decimal.js
  number-change:
    flash: "kqFlash 800ms"
    tick: "{colors.up}/{colors.down}"
    tween: 600ms   # Stat RAF;reduced-motion 直接落终值
  # 合约专用:杠杆预设档位按钮(1/2/5/10/25/50/75/100)
  # motion: 120ms 反馈(走 --motion-fast 全局基线)
  leverage-preset:
    typography: "{typography.label-caps}"
    rounded: "{rounded.xs}"
    padding: 5px 2px
    borderColorIdle: "{colors.border}"
    borderColorActive: "{colors.interactive-selected}"
    backgroundIdle: "{colors.surface-card}"
    backgroundActive: "{colors.interactive-selected}"
    textColorIdle: "{colors.text-secondary}"
    textColorActive: "{colors.text-primary}"
    fontWeight: 700
---

## Overview

KwikQuant 前端是一个冷静的量化交易工作台 —— 冷灰白画布上白卡靠底色对比出边界,粗 sans-serif display 标题直白有力,品牌橙只出现在主 CTA、focus 环与内联强调上,绝不铺底作装饰。视觉基调:冷白、亮主、留白节奏、无边界卡片。

**亮主默认**:`:root`=亮值(默认渲染)、`:root.dark`=暗值;themeStore 启动仅在 store=dark 时给 `<html>` 挂 `.dark` class。暗主题作为备选皮肤保留(`ThemeToggle` 切换),只 surface/text/border 三组随主题变;品牌橙 `--primary`、暗实心 `--onyx`、交易语义 `--up`/`--down` 双主题共用。

**品牌主色**:`{colors.primary}`(#EB8131 暖橙)只承担主 CTA 填充、focus 环、内联强调链接三件事。不铺底、不作图标装饰色、不作选中态底色 —— 橙色出现即代表"动作"。shadcn `accent` 语义复用此值;shadcn ghost/outline 的 hover 灰背景走 `--surface-hover`,不撞 `--accent`。

**粗 sans display**:display/h1/h2/h3 标题一律 `{typography.font-body}` sans-serif 栈加粗(600,hero 700),直白、现代、无装饰。正文同栈常规字重;数字用 mono 系统等宽栈 + `tnum`/`zero` feature。

**用户操作叙事旅程**:Dashboard 是主入口,沿 **编码 → 回测 → 模拟 → 实盘** 旅程引导用户,零割裂全流程。不是后台管理系统的功能堆砌 —— 违反此原则即使视觉精致也会被否定。

**关键特征:**
- 品牌橙 `{colors.primary}`(#EB8131)只留给主 CTA、focus 环、内联强调链接;装饰、图标、选中态一律中性色。
- 亮主默认,冷灰白画布 `{colors.surface-canvas}`(#EEF0F2)+ 纯白卡 `{colors.surface-card}` 靠底色对比分层;暗备选冷黑画布 + 冷深卡分层。
- 白卡无边界:不加 border、不加 shadow,圆角 `{rounded.xl}`(16px),浮在灰画布上靠对比出轮廓。
- 粗 sans display 标题(600,负字距),不加衬线、不加装饰。
- Pill geometry:每个 CTA 是 `{rounded.pill}`(999px)。
- Mono 在每个数字:资产价格、涨跌幅、订单簿、持仓、P&L —— `tnum`/`zero` feature,列对齐、实时跳动不抖。
- token 名走脚手架既有:shadcn 原生(`background`/`primary`/`accent` 等)+ 语义层(`surface-*`/`text-*`/`accent-*`/`onyx`/`up`/`down`/...),零第三套名。

## Colors

### 双主题对照

| Token | 亮(`:root`)| 暗(`:root.dark`)|
|---|---|---|
| `background` / `surface-canvas` | #EEF0F2 | #0B0E14 |
| `foreground` / `text-primary` | #11151C | #EDF0F5 |
| `card` / `surface-card` | #FFFFFF | #141821 |
| `surface-card-2` / `secondary` / `muted` | #F1F3F5 | #1B2130 |
| `surface-hover` / `interactive-hover` | #E9EBEE | #1E2431 |
| `text-secondary` / `muted-foreground` | #5C6675 | #A5AEBD |
| `text-muted` | #8B94A3 | #737E8F |
| `border` | #E1E4E8 | #262D3B |
| `border-soft` | #E9EBEE | #1E2431 |
| `accent-soft` | #FBE8D9 | #362414 |
| `accent-warm` | #8C3D0E | #F5B98A |
| `slate` | #E0E4E9 | #242B3A |
| `up` | #1E8E7E | #2BA298 |
| `down` / `destructive` | #E60050 | #F63969 |
| `up-glow` | rgba(30,142,126,.40) | rgba(43,162,152,.45) |
| `down-glow` | rgba(230,0,80,.40) | rgba(246,57,105,.45) |
| `warning` / `warning-text` | #B8740A | #E0A043 |
| `info` | #1E6FB8 | #5BA8E8 |
| `interactive-selected` | #E2E6EB | #2A3140 |
| `interactive-active` | #DCE0E6 | #262D3B |
| `interactive-disabled` | #E0E4E9 | #262D3B |

### Brand
- **Primary**(`{colors.primary}` — #EB8131 暖橙):唯一品牌色,双主题共用。**仅用于主 CTA pill 填充、focus 环、内联强调链接**;不作图标色、不作底色、不作选中态色。
- **Accent Deep**(`{colors.accent-deep}` — #C5651F):品牌深变体,主 CTA hover/active 态用(双主题共用)。
- **Accent Soft**(`{colors.accent-soft}`):品牌软底,仅 live-paper badge 等少数语义标记用。亮 #FBE8D9,暗 #362414。
- **Accent Warm**(`{colors.accent-warm}`):品牌暖文字,在 accent-soft 底上作字色。亮 #8C3D0E(深暖棕),暗 #F5B98A(浅暖橙)。

### Surface
- **Canvas**(`{colors.surface-canvas}`):默认页底。亮 #EEF0F2 冷灰白,暗 #0B0E14 冷黑。
- **Card**(`{colors.surface-card}` / `{colors.card}`):卡片底。亮 #FFFFFF,暗 #141821。白卡浮画布靠底色对比出边界,不加 border/shadow。
- **Card-2**(`{colors.surface-card-2}` / `{colors.secondary}` / `{colors.muted}`):次级面/交替带/静默底。亮 #F1F3F5,暗 #1B2130。
- **Input**(`{colors.surface-input}` / `{colors.input}`):输入框底。
- **Hover**(`{colors.surface-hover}` / `{colors.interactive-hover}`):ghost/outline button hover 灰背景、行 hover 底。
- **Onyx**(`{colors.onyx}` — #0C0F14):暗实心,双主题共用。暗 hero/CTA band/wordmark 底。
- **Slate**(`{colors.slate}`):中性次级面,值随主题变。亮 #E0E4E9,暗 #242B3A。

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
- **杠杆滑块**(`{component.leverage-slider}`):1-100x 范围,track 走 `{colors.surface-3}`,thumb 16px `{colors.onyx}` + 2px surface-card ring。120ms 拖拽反馈(走 `--motion-fast` 全局基线)。
- **杠杆预设档位按钮**(`{component.leverage-preset}`):1/2/5/10/25/50/75/100 八档。未选中 surface-card 底 + text-secondary 字;选中 interactive-selected 中性底 + text-primary 字。
- **保证金模式 tab(逐仓 / 全仓)**:全仓 disabled + tooltip "开发中"。disabled 态走 `{colors.interactive-disabled}` 底 + `{colors.text-muted}` 字 + opacity 0.55。
- **底部信息行(强平价 / 保证金率 / 保证金占用)**:`{typography.font-mono}` + 弱化字色(`{colors.text-muted}` / `{colors.text-secondary}`),不抢主视觉。强平价走 `{colors.warning}` 文字色 + 700 weight;保证金率随档位变:`>80%` 走 down、`>50%` 走 warning、其余走 text-secondary。
- **持仓表合约列**:当持仓含 PERP 态时,补显示杠杆 / 保证金模式 / 标记价 / 强平价四列。SPOT 态在合约列显 "—"(text-muted)。强平价列用 `{colors.warning}` 字色 + 700 weight 提示风险。

### Interactive States
- **Hover**(`{colors.interactive-hover}` = `{colors.surface-hover}`):hover 灰背景。
- **Active**(`{colors.interactive-active}`):按下态深灰。
- **Selected**(`{colors.interactive-selected}`):选中态中性冷灰底 —— 不用品牌橙。亮 #E2E6EB,暗 #2A3140。
- **Disabled**(`{colors.interactive-disabled}`):禁用底色。

## Typography

### Font Family
两族系统字体栈,**不加载外部字体**(无 `@font-face`,无 webfont 请求)。font-family 栈声明 Inter 但系统回退 —— 首屏快,零字体加载,零 FOUT/FOIT。

- `{typography.font-body}` — Inter sans-serif 栈(回退 -apple-system / BlinkMacSystemFont / Segoe UI / system-ui / PingFang SC / Hiragino Sans GB / Microsoft YaHei)。display 标题与 body 共用此栈,标题加粗(600,hero 700)。
- `{typography.font-mono}` — 系统等宽栈(ui-monospace / SF Mono / Menlo / JetBrains Mono / Cascadia Code / Roboto Mono)。所有数字用此 + `tnum`/`zero` feature。

### Hierarchy

| Token | Size | Weight | LH | Tracking | Use |
|---|---|---|---|---|---|
| `{typography.display}` | 38px | 600 | 1.1 | -0.025em | 页面主标题 |
| `{typography.h1}` | 30px | 600 | 1.15 | -0.02em | 段标题 |
| `{typography.h2}` | 22px | 600 | 1.3 | -0.01em | 卡组标题 |
| `{typography.h3}` | 17px | 600 | 1.2 | -0.01em | 组件标题 |
| `{typography.body}` | 14px | 400 | 1.6 | 0 | 默认正文 |
| `{typography.body-sm}` | 13px | 400 | 1.4 | 0 | 紧凑正文、按钮 |
| `{typography.caption}` | 12px | 400 | 1.5 | 0 | 说明、副文本 |
| `{typography.label-caps}` | 11px | 600 | 1.4 | 0.05em | badge label、字段标签(caps) |
| `{typography.mono}` | 13px | 500 | 1.4 | 0 | 数字/金额 — mono |

### Principles
- **标题靠字重分级,不靠字体族。** display/h1/h2/h3 与 body 同一 sans-serif 栈,标题 600、hero 700、正文 400 —— 层级清晰、无混排。
- **负 tracking 只在 display。** display 用 -0.02em 到 -0.025em;body 保持 0。
- **Mono 在每个数字。** 资产价格、涨跌幅、订单簿、持仓、P&L —— 任何 tabular 数字用 `{typography.font-mono}` + `tnum`/`zero` feature,列对齐、实时跳动不抖。
- **不加载外部字体。** 栈声明 Inter 但无 `@font-face`,系统回退。首屏快;代价是跨机字形不一致(见 Known Gaps)。

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
- **Section padding:** `{spacing.section}`(96px)用于每个主要页面分段。
- **Card internal padding:** 24px(`{spacing.lg}`)用于标准卡,32px(`{spacing.xl}`)用于 feature 卡。

### Whitespace Philosophy
慷慨留白节奏 —— 96px 段间距;段内卡片 24px 间距。密度留给数据密集表格和交易面,营销段靠留白呼吸。

## Elevation & Depth

| Level | Treatment | Use |
|---|---|---|
| Flat | 无阴影无边框 | 默认表面;白卡在灰画布上靠底色对比分层 |
| Hairline border | 1px `{colors.border}` | 输入框、表格行、结构分隔;不给卡片描边 |
| Pop | `{shadow.pop}` | popover/dialog/dropdown 浮层 |
| Control | `{shadow.control}` | 悬浮小控件(侧栏折叠钮等) |

### Motion
- **Fast**(`{motion.fast}` 120ms):hover/toggle 微反馈。`--default-transition-duration` 收编到 fast,未显式带 duration 的过渡不再落框架默认 150ms。
- **Base**(`{motion.base}` 200ms):默认过渡。
- **Slow**(`{motion.slow}` 300ms):展开/收起、抽屉。
- **theme-swap**:主题切换走 View Transitions 交叉过渡;不支持或 reduced-motion 直切。
- **内容切换**:TabsContent 淡入(`{motion.tab-content}`)、行/区块展开(`{motion.expand}`,collapsible keyframes)、新通知/新行入场高亮(`{motion.list-enter}`,kqFlash)。
- **数字反馈**:余额/总资产/盈亏跳变走 `{component.number-change}`(FlashNumber),首帧与等值不闪。
- `prefers-reduced-motion: reduce` 兜底:animation/transition duration 降到 0.01ms(index.css 全局基线);SVG 脉冲等一律 CSS 动画实现(不用 SMIL,否则兜底管不到)。
- **滚动条**:所有 overflow 容器挂 `.kq-thin-scroll`(thin、默认透明、hover 显 border 色),系统粗滚动条不进场。

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
shadcn `Card` 原子。底 `{colors.surface-card}`,字 `{colors.text-primary}`,`{rounded.xl}`,padding 24px(`{spacing.lg}`)。**无 border、无 shadow** —— 白卡浮在 `{colors.surface-canvas}` 灰画布上靠底色对比出边界。不做 hover 浮起;可点击卡片用 `hover:bg-{colors.surface-hover}` 之类底色反馈。灰底段(`{colors.surface-card-2}`)上需要卡时,改用 `{colors.surface-card}` 白卡,同样无边界。

### Button
shadcn `Button` 原子,默认 `{rounded.pill}` height 40px。sm 档 32px 为密集场景豁免(表格行/工作台 sub-header);icon 档走 `{rounded.full}` 圆盘。
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
- **nav-active**(`{component.nav-active}`):侧栏选中项。底 `{colors.interactive-selected}`(中性冷灰,不带品牌色),字 `{colors.text-primary}`,`{rounded.sm}`,无指示条。
- **live-paper-badge**(`{component.live-paper-badge}`):PAPER 模拟盘标记。底 `{colors.accent-soft}`,字 `{colors.accent-warm}`,`{typography.label-caps}`,`{rounded.pill}`。与 LIVE 实盘必须视觉强区分(见 Do's and Don'ts)。
- **perp-market-badge**(live-paper-badge 同款 token,显式豁免):PERP 合约市场标记(回测报告/任务卡/持仓表"合约"pill)。复用 `{colors.accent-soft}`/`{colors.accent-warm}`——accent 色对的语义是"品牌软底少数语义标记"(见 Accent Soft 条款),模拟盘与合约两个维度不共现于同一 pill 位(持仓行:模拟/实盘 badge 与合约 badge 并列但形态不同),混淆风险可接受;新增市场维度标记一律走本条款,不得再自造色对。
- **status-dot**(`{component.status-dot}`):连接状态点。底 `{colors.up}`(connected)/ `{colors.down}`(error),`{rounded.full}`,8px。

## Do's and Don'ts

### Do
- `{colors.primary}`(#EB8131)只留给主 CTA 填充、focus 环、内联强调链接、品牌 logo 图形。其余场景(图标、选中态、数字、圆点、标签)一律中性色。
- 每个 CTA 用 `{rounded.pill}`(999px);每个资产 glyph 用 `{rounded.full}`;每张卡用 `{rounded.xl}`(16px)。
- 标题用 `{typography.font-body}` 栈加粗(600,hero 700),与正文同族靠字重分级。
- 卡片无边界:白卡浮灰画布靠底色对比;需要分隔用留白或背景色切换,不用 border 描边。
- 每个数字用 `{typography.font-mono}` + `tnum`/`zero` feature —— 金额一律 `decimal.js`(`src/lib/money.ts` 是唯一入口),`parseFloat`/`Number` 参与金额运算被 ESLint 硬拦。
- 涨跌不靠颜色单独表达:配 ↑↓ 箭头 + 文本标签(a11y WCAG 2.2 AA)。up/down 语义色与品牌色分离。
- 营销页用 `{colors.surface-canvas}` / `{colors.surface-card}` 段交替作页面节奏;亮主默认,暗为备选。
- token 名走脚手架既有:shadcn 原生(`bg-background`/`bg-primary`/`bg-accent`)+ 语义层(`bg-surface-canvas`/`text-text-primary`/`bg-accent-soft`)。
- **合约 4 按钮(开多/开空/平多/平空)走 `{component.position-effect-button}`**:开仓态实色填充 + 白字,平仓态弱化半透明 + 对应字色,未选中弱化态。
- **杠杆控件走中性色**:`{component.leverage-slider}` + `{component.leverage-preset}` 用中性灰黑,不混用 up/down,也不带品牌橙。
- **持仓表 PERP 态显合约列、SPOT 态显 —**:不删列,跨 marketType 列结构保持一致(对齐体验)。

### Don't
- 不引入第二品牌色。`{colors.primary}`(#EB8131)是唯一动作色;交易绿/红是 semantic-only(买卖 CTA `order-form-cta-buy/sell` 是唯一背景填充例外)。**合约 long/short 是语义别名,值复用 up/down,不引第三/第四套色;强平价直接复用 warning 不另立 liquidation 别名。**
- **不把品牌橙当装饰色**:图标着色、kicker 标签、统计数字、圆点、选中态底色、卡片软底铺色都不用橙 —— 这些场景用中性色(`{colors.text-*}`/`{colors.surface-*}`)。橙色出现即代表动作(主 CTA / focus / 内联链接)。
- 不用纯黑 `#000` —— 用 `{colors.onyx}`(#0C0F14 冷黑)。
- 不给卡片加 border + shadow —— 白卡靠底色对比出边界;浮层只用 `{shadow.pop}`,悬浮小控件用 `{shadow.control}`。
- 不在 CTA 上用锐角(`{rounded.none}` 不定义)。
- 不在标题里混用字体族 —— 全站单一无衬线栈,标题靠字重分级。
- 不用交易绿/红作按钮背景(买卖 CTA 是唯一例外)。
- 不引入第三套 token 名(`--canvas`/`--ink`/`--brand` 等)—— 脚手架既有名(shadcn 原生 + 语义层)是唯一 token 体系。
- **PAPER 模拟盘 vs 实盘必须视觉强区分**:用户绝不能误把实盘当模拟盘下单。用 live-paper badge 标记/颜色/确认弹窗多层防护。
- 不硬编码颜色/圆角/字号(`#000`/`#fff`/`24px` 等)—— token 走 `DESIGN.md` → `index.css` → 组件类。
- **不在合约 4 按钮上混用 positionEffect 枚举英文作主文案**:用户可见文案是中文(开多/开空/平多/平空)。枚举(OPEN_LONG 等)只作辅助小字标签,且字号 ≤ 9.5px。
- **不在杠杆滑块上用 up/down 色或品牌橙**:杠杆是控件而非方向也非动作,走中性色。

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
- topbar <980px 面包屑折叠为当前页名;搜索框 <1280px 折 icon(980–1280 带宽扣除侧栏后放不下 280px 框 + 面包屑;「返回」affordance 待实现)。
- feature card grid:3-up → 2-up → 1-up。
- 数字表 <760px 横向滚动(首列 sticky),不堆叠(保持行对齐)。

### Touch Targets
- 主 CTA pill 40px height — WCAG AA。
- icon button 36px — 边界。
- nav item 40px height — 有效 tap zone。

## Iteration Guide

1. 一次聚焦一个组件。直接引用 YAML key,不内联 hex。
2. 新 CTA 默认 `{rounded.pill}`(999px);新 icon plate 默认 `{rounded.full}`;卡用 `{rounded.xl}`(16px),无 border 无 shadow。
3. 变体作为 `components:` 块内独立条目。
4. 到处用 `{token.refs}` —— 不内联 hex。
5. Hover 态默认走 `{colors.surface-hover}`(ghost/outline)或 `{colors.interactive-hover}`(行);active 走 `{colors.interactive-active}`;selected 走 `{colors.interactive-selected}`(中性冷灰,不带品牌色)。
6. display/h1/h2/h3 600,hero 700,body 400/600,body-sm 400,mono 在每个数字(weight 500)。
7. `{colors.primary}`(#EB8131)只出现在动作点 —— 主 CTA、focus 环、内联强调链接。装饰一律中性色。

## Known Gaps

- **字体跨机一致性**:font-family 栈声明 Inter 但无 `@font-face`,系统回退。跨机字形不一致(各平台系统无衬线字体回退);代价是首屏快、零字体加载。后续如需品牌字形一致性,再评估加载 webfont。
- **type 派生**:font-size scale 是手工定义的,未从 ratio 自动派生。调整 scale 需同步改 index.css 与 DESIGN.md 两处。
- **暗主题打磨度**:本次改版以亮主题(冷白)为主做视觉重构,暗主题(`:root.dark`)只同步了中性色板冷化,细部对比度与层次待实际使用后微调。
