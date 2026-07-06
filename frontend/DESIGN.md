---
# KwikQuant 前端设计契约(DESIGN.md)
# 采用 Google DESIGN.md 规范(google-labs-code/design.md,Apache-2.0,alpha)。
# 本文件是前端 UI 唯一真相源:YAML 头 = 机读 token(规范值),正文 = 人读 rationale。
# 视觉锚点:docs/design-ref/done-ai/(已验证原型,Airbnb 卡片 + 暖铜色,2026-07-04 用户拍板)。
# Token 流转路径唯一:DESIGN.md(本文件)→ src/index.css(CSS 变量)→ React 组件 + Tailwind。
# 三者冲突时,以本文件为准;本文件与原型冲突时,以原型为准并回头修本文件。
#
# 双主题:暗主为默认皮肤(token 取暗主题值),亮备选在正文 §Colors 描述映射。
# 营销腔文案(Superhost / 4,200 quant desks / 4.94★)是品牌封面示例,产品页剥除。

version: "alpha"
name: "KwikQuant"
description: "开源、自托管、零信任的一站式加密量化交易工作台前端。Airbnb 卡片 + 暖铜色 + 双主题暗主,视觉锚点 docs/design-ref/done-ai/。"

# ===== 颜色 =====
# 命名:B 方案 — 主色叫 primary(消 lint missing-primary warning),其余色锁原型 done-ai/ 的语义名。
# 理由:① 零重复(不写别名);② 除主色外跟原型 @theme 一一对应,agent 对照原型零翻译;
#      ③ 主色 primary=Graphite 的映射在正文 §Colors 写明,色彩叙事不丢。
# 原型 @theme 名对照:primary=Graphite · onyx=Onyx · slate=Slate · accent=Copper · accent-warm=Brass · up=Babu · down=Signal Down
colors:
  # --- 品牌主色(Graphite 系)---
  primary: "#1F2937"            # Graphite · Primary / CTA / 主按钮底(= 原型 --color-graphite)
  onyx: "#0F172A"               # Onyx · 暗主题 canvas / 按下态(= 原型 --color-onyx / --color-graphite-deep)
  slate: "#374151"              # Slate · 正文 body(= 原型 --color-slate / --color-graphite-soft)
  on-primary: "#F8FAFC"         # 主色上的前景文字(Graphite 50,暗主题字色)

  # --- 强调色(Copper 系,仅作点缀/章节/关键交互,不大面积)---
  accent: "#C2410C"             # Copper · 章节标 / 链接 hover / focus 内环
  accent-deep: "#9A3412"        # Copper-deep · 按下态
  accent-soft: "#EA7C3E"        # Copper-soft · 小字/focus 外环(暗主题下 Copper 仅 3.2:1,用此提对比)
  accent-warm: "#B45309"        # Brass · 暖细节
  on-accent: "#FFFFFF"          # 铜色上的白字

  # --- 文本层级(三档)---
  text-primary: "#F8FAFC"       # 暗主题标题/高亮;亮主题 → #0F172A(见 §Colors)
  text-secondary: "#94A3B8"     # 暗主题正文副;亮主题 → #374151
  text-muted: "#6B7280"         # Steel · 元信息/副文本(双主题共用)

  # --- 语义色(涨跌,与品牌色完全分离)---
  up: "#2BA298"                 # 涨 / 盈利 / 正向
  down: "#F63969"              # 跌 / 亏损 / 负向
  warning: "#FF990A"            # 警告
  warning-bg: "#FF990A22"       # 警告底(带透明度)
  warning-text: "#C36000"       # 警告文本
  info: "#52AEFF"               # 信息

  # --- 表面/容器(暗主题为默认)---
  surface-canvas: "#0F172A"     # 暗主题 canvas;亮主题 → #FAF8F3 Cream
  surface-card: "#1E293B"       # 暗主题卡片;亮主题 → #FFFFFF
  surface-card-2: "#334155"     # 暗主题次级卡面;亮主题 → #F2F1EC Foggy
  surface-input: "#0F172A"      # 暗主题输入框;亮主题 → #FFFFFF
  surface-hover: "#1E293B"      # hover 态叠加(暗);亮主题 → #F2F1EC

  # --- 边框/分隔 ---
  border: "#334155"             # 暗主题主边框;亮主题 → #E3E1DA Mist
  border-soft: "#1E293B"        # 暗主题软分隔;亮主题 → #F2F1EC

  # --- 交互态(4 态全覆盖:hover/active/selected/disabled)---
  interactive-hover: "#EA7C3E"  # hover 铜色
  interactive-active: "#9A3412"  # active 按下瞬时态(比 selected 更深一号,同 accent-deep)
  interactive-selected: "#C2410C"  # 选中铜色
  interactive-disabled: "#6B728055"  # 禁用(带透明度,不发亮)

# ===== 排版 =====
typography:
  # --- 字体族(三件套,全系统栈,不加载外部字体)---
  # 2026-07-06 用户拍板:删 @fontsource/CDN,全系统字体栈,加载更快。数字等宽靠 font-mono 系统等宽栈 + tnum feature。
  font-display:
    fontFamily: "'Times New Roman', Georgia, 'Songti SC', 'STSong', serif"
  font-body:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', system-ui, sans-serif"
  font-mono:
    fontFamily: "ui-monospace, 'SF Mono', 'Cascadia Code', 'Roboto Mono', Menlo, Consolas, 'Liberation Mono', monospace"
    fontFeature: "'tnum','zero'"

  # --- 字号层级(原型:Display 96 / H1 48 / H2 32 / H3 22 / Body 16 / Body-sm 13 / Caption 12)---
  display:
    fontFamily: "'Times New Roman', Georgia, 'Songti SC', 'STSong', serif"
    fontSize: "96px"
    fontWeight: "400"
    letterSpacing: "-0.02em"
    lineHeight: "0.95"
  h1:
    fontFamily: "'Times New Roman', Georgia, 'Songti SC', 'STSong', serif"
    fontSize: "48px"
    fontWeight: "500"
    lineHeight: "1.1"
  h2:
    fontFamily: "'Times New Roman', Georgia, 'Songti SC', 'STSong', serif"
    fontSize: "32px"
    fontWeight: "500"
    lineHeight: "1.2"
  h3:
    fontFamily: "'Times New Roman', Georgia, 'Songti SC', 'STSong', serif"
    fontSize: "22px"
    fontWeight: "500"
    lineHeight: "1.3"
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: "400"
    lineHeight: "1.75"
  body-sm:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: "13px"
    fontWeight: "400"
    lineHeight: "1.5"
  caption:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: "400"
    lineHeight: "1.4"
  label-caps:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', system-ui, sans-serif"
    fontSize: "11px"
    fontWeight: "600"
    letterSpacing: "0.35em"
    lineHeight: "1.2"
  mono:
    fontFamily: "ui-monospace, 'SF Mono', 'Cascadia Code', 'Roboto Mono', Menlo, Consolas, 'Liberation Mono', monospace"
    fontSize: "13px"
    fontWeight: "500"
    fontFeature: "'tnum','zero'"
    lineHeight: "1.4"

# ===== 圆角(scale-level)=====
rounded:
  sm: "8px"                     # subcard 内小块 / badge
  md: "12px"                    # 按钮 / 输入
  lg: "16px"                    # 卡内次级块
  xl: "24px"                    # 主卡片(Airbnb)
  "2xl": "28px"                 # 大块 hero
  full: "999px"                 # chip / pill

# ===== 间距(scale-level)=====
spacing:
  sm: "8px"
  md: "16px"
  lg: "24px"                    # 卡片 padding
  xl: "32px"                    # 大卡 padding / 章节内距
  "2xl": "48px"
  "3xl": "96px"                 # 章节间距(section-gap)

# ===== 阴影(elevation)=====
# 自定义扩展键,规范 linter 对 custom extension key 静默不报错。
elevation:
  card: "0 6px 16px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04)"
  card-hover: "0 20px 40px rgba(0,0,0,0.08), 0 2px 4px rgba(0,0,0,0.05)"
  float: "0 1px 3px rgba(0,0,0,0.08), 0 8px 24px rgba(0,0,0,0.12)"

# ===== 动效(自定义扩展)=====
motion:
  fast: "120ms cubic-bezier(.2,.9,.3,1.4)"
  normal: "200ms cubic-bezier(.2,.9,.3,1.4)"
  card-hover: "350ms cubic-bezier(.2,.9,.3,1.4)"
  reduced: "0.01ms"

# ===== 组件(YAML token 引用,lint 可查对比度)=====
components:
  # --- 按钮(4 variants)---
  # button-primary 反色方案(2026-07-06 用户拍板 override,推翻原"双主题共用 Graphite 底"意图):
  #   浅色: bg-onyx(#0F172A 黑底) + text-on-primary(#F8FAFC 白字) → 14:1 对比度
  #   深色: bg-white(白底) + text-onyx(#0F172A 黑字) → 14:1 对比度
  # 理由:双主题共用 Graphite(primary #1F2937)做主按钮底,在暗主题 canvas(#0F172A Onyx)上对比度不足、
  #      轮廓消失;反色 CTA 在暗主题更醒目(Stripe/Linear 式),双主题都达 14:1。
  # 组件层实现: bg-onyx text-white dark:bg-white dark:text-onyx(token 链:onyx/white 双主题共用,
  #   浅深反色靠 dark: 前缀切换,不引入新 token)。
  button-primary:
    backgroundColor: "{colors.onyx}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.md}"
    typography: "{typography.body-sm}"
  button-primary-hover:
    backgroundColor: "{colors.primary}"   # 浅色 hover:onyx→primary(深灰,#0F172A→#1F2937)
  button-copper:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    rounded: "{rounded.md}"
    typography: "{typography.body-sm}"
  button-copper-hover:
    backgroundColor: "{colors.accent-deep}"
  button-ghost:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.md}"
    typography: "{typography.body-sm}"
  button-ghost-hover:
    backgroundColor: "{colors.surface-hover}"
  button-icon:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.accent}"
    rounded: "{rounded.full}"
    size: "44px"

  # --- 输入框 ---
  input:
    backgroundColor: "{colors.surface-input}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.full}"
    typography: "{typography.body-sm}"
  input-focus-ring:
    backgroundColor: "{colors.accent-soft}"

  # --- 卡片(Airbnb,主层级手段)---
  card:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  card-hover:
    backgroundColor: "{colors.surface-card}"
  strategy-card-hero:
    backgroundColor: "linear-gradient(135deg, {colors.primary} 0%, {colors.onyx} 60%, {colors.accent} 130%)"

  # --- Chip ---
  chip:
    backgroundColor: "{colors.surface-card-2}"
    textColor: "{colors.text-secondary}"
    rounded: "{rounded.full}"
    typography: "{typography.caption}"

  # --- 数据行(mono 数字)---
  data-row-mono:
    typography: "{typography.mono}"
    textColor: "{colors.up}"  # 示例:涨用 up;实际按数据动态 up/down

  # --- 阶段面包屑当前态 ---
  breadcrumb-active:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.accent}"
    rounded: "{rounded.md}"
    typography: "{typography.body-sm}"
---

# KwikQuant 前端设计契约

> **唯一真相源**。Token 流转:`DESIGN.md`(本文件)→ `src/index.css`(CSS 变量)→ React 组件 + Tailwind。三者冲突以本文件为准;本文件与原型 `docs/design-ref/done-ai/` 冲突,以原型为准并回头修本文件。
>
> 本文件由 `docs/ux-design.md`(设计原则叙述)工程化为 Google DESIGN.md 规范契约。`ux-design.md` 保留设计原则叙述,本文件是 AI/CI 可机读的工程约束。两份文档分离并存。

## Overview

KwikQuant = 开源、自托管、零信任的一站式加密量化交易工作台。

视觉语言全盘照搬 Done AI 品牌手册(`docs/design-ref/done-ai/`,2026-07-04 用户实际看到产出质感后拍板 override)。核心特征:**Airbnb 卡片风**(24-32px 大圆角 + drop shadow + hover translateY)+ **暖铜色单强调** + **双主题暗主亮备选** + **字体三件套**(系统衬线 display / 系统无衬线 body / 系统等宽 mono,2026-07-06 改全系统栈不加载外部字体)。差异化于"又一个蓝色 crypto app"与纯黑高密度券商终端。

设计评判尺子:**高级干净优先于信息密度**。drop shadow 是主层级手段,发丝边框仅作 divider,不作主层级。

营销腔文案(Superhost of Quant / Trusted by 4,200+ quant desks / 4.94★)是品牌封面示例,**产品页剥除**——个人量化工具不卖 SaaS。

## Colors

色板锁定(原型已验证)。Graphite 承担品牌与结构,Copper 与 Brass 用于点缀/章节/关键交互。Up/Down 双语义色与主色**完全分离**——交易员看数据不被主色干扰。

**品牌主色:**
- **primary (#1F2937 Graphite):** Primary / CTA / 主按钮底(= 原型 --color-graphite)。
- **onyx (#0F172A Onyx):** 暗主题 canvas / 按下态(= 原型 --color-onyx)。
- **slate (#374151 Slate):** 正文 body(= 原型 --color-slate)。

**强调色(Copper 系,小面积点缀):**
- **accent (#C2410C Copper):** 章节标 / 链接 hover / focus 内环(= 原型 --color-copper)。
- **accent-deep (#9A3412):** 按下态(= 原型 --color-copper-deep)。
- **accent-soft (#EA7C3E):** 小字/focus 外环(= 原型 --color-copper-soft)— **暗主题下 Copper #C2410C 仅 3.2:1 不达 WCAG AA,小字/focus 必须用此色**。
- **accent-warm (#B45309 Brass):** 暖细节(= 原型 --color-brass)。

**文本层级(三档):**
- **text-primary:** 标题/高亮。
- **text-secondary:** 正文副。
- **text-muted (#6B7280 Steel):** 元信息/副文本(= 原型 --color-slate-airbnb,双主题共用)。

**语义色(涨跌,与品牌色完全分离 — 交易员看数据不被主色干扰):**
- **up (#2BA298):** 涨 / 盈利 / 正向(= 原型 --color-up / --color-babu)。
- **down (#F63969):** 跌 / 亏损 / 负向(= 原型 --color-down)。
- **warning (#FF990A) / info (#52AEFF):** 警告/信息。

**表面/容器(暗主题为默认):**
- **surface-canvas (#0F172A):** 暗 canvas。
- **surface-card (#1E293B):** 暗卡片。
- **surface-card-2 (#334155):** 暗次级卡面。
- **surface-input (#0F172A):** 输入框底。

**边框:**
- **border (#334155):** 主边框。
- **border-soft (#1E293B):** 软分隔。

**交互态(显式定义,AI 最容易漏 — 必须用,不许自造):**
- **interactive-hover (#EA7C3E):** hover 铜色。
- **interactive-selected (#C2410C):** 选中铜色。
- **interactive-disabled (#6B728055):** 禁用(带透明度,不发亮)。

### 双主题映射

YAML 头取**暗主题值**(默认皮肤)。**亮备选**用同 token 名映射不同值,落 `src/index.css` 的 `:root`(亮)+ `.dark`(暗):

| Token | 暗主题(默认,YAML 值) | 亮备选(:root) |
|-------|----------------------|----------------|
| surface-canvas | #0F172A Onyx | #FAF8F3 Cream |
| surface-card | #1E293B | #FFFFFF |
| surface-card-2 | #334155 | #F2F1EC Foggy |
| surface-input | #0F172A | #FFFFFF |
| text-primary | #F8FAFC | #0F172A |
| text-secondary | #94A3B8 | #374151 |
| border | #334155 | #E3E1DA Mist |
| border-soft | #1E293B | #F2F1EC |

切换:由 `stores/themeStore.ts` 的 `colorScheme` 控制,应用到 `<html class="dark">`。persist 到 localStorage(key `kwikquant-theme`)。组件只引用 token 名,主题切换透明。

### a11y(WCAG 2.2 AA,硬约束)

- Copper #C2410C 作小字 on dark 仅 3.2:1 **不达标**,小字/focus 用 `accent-soft #EA7C3E`。
- 正文对比度 ≥ 4.5:1,UI 边界 ≥ 3:1。
- 状态不单靠颜色(配文本标签:"实盘/模拟/编码"、↑↓ 箭头)。
- 全键盘 + 可见焦点 + 200% 缩放。
- `prefers-reduced-motion` 兜底(动效降为 `motion.reduced`)。

## Typography

三层字体分工(见原型 TypeSection):

- **系统衬线(font-display):** 品牌叙事与展示。**仅登录/营销页 display,不进工作区**。Display 96 / H1 48 / H2 32 / H3 22。
- **系统无衬线(font-body):** UI 清晰。Body 16 / Body-sm 13 / Caption 12 / label-caps 11(uppercase tracking 0.35em,章节标用)。
- **系统等宽(font-mono):** 数字对齐。`tnum` + `zero` feature(金融/量化终端标配)。所有展示数字、金额、PnL、sharpe、trade count 一律 font-mono。

**字重上限 600**(禁 700,原型无 700)。

字阶见 YAML `typography.*`。字体策略(2026-07-06 用户拍板):**全系统字体栈,不加载任何外部字体**(@fontsource/CDN 全删,用户加载更快)。数字等宽靠 font-mono 系统等宽栈 + html 全局 `font-feature-settings:'tnum','zero'`。三档分工保留(display 衬线 / body 无衬线 / mono 等宽),具体栈见 YAML `typography.font-*`。

## Layout

**间距(scale-level,见 YAML `spacing`):** sm 8 / md 16 / lg 24 / xl 32 / 2xl 48 / 3xl 96。

**页宽:** 1240px(max-width)。页面左右 padding `spacing.xl`(32px)。

**章节间距:** `spacing.3xl`(96px)。

**栅格:** 12 列,gap `spacing.md`(16px)。

## Elevation & Depth

**drop shadow 是主层级手段**,发丝边框仅作 divider/section 分隔,不作主层级。

- **elevation.card:** `0 6px 16px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04)` — 卡片静态。
- **elevation.card-hover:** `0 20px 40px rgba(0,0,0,0.08), 0 2px 4px rgba(0,0,0,0.05)` — 卡片 hover。
- **elevation.float:** `0 1px 3px rgba(0,0,0,0.08), 0 8px 24px rgba(0,0,0,0.12)` — 浮动元素(下拉/弹出/tooltip)。

**hover 位移:** 主卡 hover `translateY(-3px)` + `elevation.card-hover`,350ms `cubic-bezier(.2,.9,.3,1.4)`。

**零光效:** 禁 backdrop-blur(无玻璃态)。层级靠 drop shadow,不靠模糊。

## Shapes

**圆角(scale-level,见 YAML `rounded`):** sm 8 / md 12(按钮·输入)/ lg 16(次级卡)/ xl 24(主卡 Airbnb)/ 2xl 28(hero)/ full 999(chip·pill)。

**卡片必须 `rounded.xl`(24px)起** — 这是 Airbnb 卡片风的硬要求,禁小圆角卡片。

## Components

核心组件见 YAML `components.*`。每种组件 AI 实现时**禁止凭空增加规范里没有的样式**(如主按钮多加图标、幽灵按钮去边框)。

### 按钮(4 variants)

| 组件 | 用途 | 背景 | 文字 | 圆角 | hover |
|------|------|------|------|------|-------|
| `button-primary` | 页面唯一主操作 | 反色:浅 onyx 黑底白字 / 深 白底 onyx 黑字 | on-primary(浅)·onyx(深) | md(12px) | primary(浅)·white/90(深) + translateY(-1px) |
| `button-copper` | 关键强调(部署/确认) | accent(Copper)渐变 → accent-deep | on-accent(白) | md | accent-deep + translateY(-1px) |
| `button-ghost` | 内联轻量操作 | surface-card + 1px border | text-primary | md | **必须保留边框**;hover surface-hover |
| `button-icon` | 工具栏 | primary / accent / ghost 三选一 | — | full(圆形 999) | 44×44,no padding-around;hover scale(1.05) |

**通用**:disabled 用 `interactive-disabled`(带透明度,不发亮)。**禁止纯黑 #000 作 CTA** — 用 `onyx #0F172A`。

### 输入框 & Chip

- **input:** pill(`rounded.full`)+ 内嵌 label(11px label-caps text-secondary)+ body-sm 值。focus 态:`outline 2px accent-soft + 4px ring accent`。有内容时深浅反转保一致(参考原型 Symbol/Cycle/Search 多字段组)。
- **chip:** pill + surface-card-2 底 + 1px border + 12px text-secondary。hover → border 转 accent + 文字转 accent。可作为多选标签(Spot/Perp/Grid/AI-Assisted/Trailing Stop…)。

### 卡片(Airbnb 风)

- **card:** `rounded.xl`(24px) + `elevation.card` + `surface-card` 底 + `padding.lg`(24px)。hover `translateY(-3px)` + `elevation.card-hover` 350ms。
- **次级卡:** `rounded.lg`(16px),无阴影或 `elevation.float`。
- **strategy-card:** 24px + 顶部 40px 渐变 hero(`strategy-card-hero` Graphite→Onyx→Copper 130%)+ 右上角 marquee-badge(Sharpe 等)。点卡 → 工作区。

### 数据行 / 表格

- **Portfolio Row:** 头像(32×32 圆,`bg-up`/`bg-down`)+ 名称 text-secondary + 右侧 mono 数字。hover surface-card-2。**数字一律 font-mono tnum**。
- **数据表头:** label-caps(11px uppercase tracking 0.35em text-muted)。

### 导航 / 面包屑

- **侧栏 icon rail:** 6 项可折叠,寻路用,非首屏入口。
- **阶段面包屑:** `编码 › 回测 › 模拟 › 实盘`,当前态 `breadcrumb-active`(primary 底 + accent 字),高亮可跳,带状态徽章。

## Do's and Don'ts

### Agent 实现约束(拦截条款 — 本节最关键)

AI agent 在做任何 UI 工作前,**必须先读完本文件**。

**前置必读:**
1. 必须先读本文件(`frontend/DESIGN.md`)。
2. 必须使用其中定义的 Token(CSS 变量或 Tailwind 类名),禁止硬编码颜色值。
3. 改存量代码时,优先复用邻近代码已用的 Token;若邻近代码本身违规,按本文件纠正而非照抄。

**遇冲突的拦截流程:**

遇到与本文 Token 冲突的需求(例如"把按钮改成纯黑 #000"、"hover 改成 #333"、"卡片加 backdrop-blur"),**不得静默执行**:
1. 标注冲突置信度(high / medium / low)。
2. 引用违反的具体条款(本文件第几节第几行)。
3. 给出 Token 化的替代方案(如"若要更深的主按钮,用 `onyx #0F172A`,不是 #000")。
4. 反问是否需要破例,等用户确认。

### ❌ 硬禁止清单(基于原型已验证,违反即破视觉锚点)

- ❌ 禁止硬编码颜色值(`#000`、`#fff`、`rgb(...)`、`hsl(...)`),必须用 CSS 变量或 Tailwind 类名。
- ❌ 禁止纯黑 `#000` 作为 CTA(用 `onyx #0F172A`)。
- ❌ 禁止 `backdrop-blur`(零光效,原型无玻璃态;层级靠 drop shadow)。
- ❌ 禁止小圆角卡片(`rounded.md` 及以下用于卡片);卡片必须 `rounded.xl`(24px)起。
- ❌ 禁止凭空增加组件规范里没有的样式(主按钮多加图标、幽灵按钮去边框、卡片内嵌 Recharts)。
- ❌ 禁止 700 字重(原型字重上限 600)。
- ❌ 禁止营销腔文案(Superhost / 4,200 quant desks / 4.94★ 等)进产品页。

### 数字与金额红线(金融前端特有)

- 金额一律 `decimal.js`,禁止 `Number()` / `parseFloat()` 参与金额运算(JS double 丢精度)。
- 所有展示数字用 `font-mono` + `tnum` feature(列对齐 + 实时跳动不抖)。
- 涨用 `up`、跌用 `down`,**不靠颜色单独表达**(配 ↑↓ 箭头 + 文本标签,a11y WCAG 2.2 AA)。

### 关键原则

1. **每个颜色都要有语义名**(primary / accent / up / down / onyx / slate,不要 color-1 / #fff)。
2. **交互态必须显式定义**(interactive-hover / selected / disabled),AI 最容易漏。
3. **先少后多**:从当前 token 起步,缺啥加啥,不堆 120 个。
4. **视觉锚点优先**:有疑问先看 `docs/design-ref/done-ai/`,原型是比本文更直接的真相。
5. **约束是护栏不是枷锁**:本约束基于已验证原型,保证不漂移;AI 仍有美学判断空间(间距微调、组件组合方式),只要不破 token 和硬禁止清单。