# src/types

后端契约生成的类型落点。

- `api-gen.ts` —— 由 `pnpm gen:api` 从后端 OpenAPI 契约（`/v3/api-docs`）生成，**禁止手写/手改**。生成物进 git 追踪，`gen:api:check` 靠 `git diff` 拦契约漂移（见下）。
- `src/api/` 层负责把生成类型翻译为前端领域模型（业务阶段规范）。

## 生成

```bash
# 后端需在 8080 运行（默认抓 http://localhost:8080/v3/api-docs）
pnpm gen:api
# 自定义地址：
KWIKQUANT_API_DOCS=http://host:port/v3/api-docs pnpm gen:api
```

## drift 守护

`pnpm gen:api:check` 重新生成并 `git diff --exit-code`：契约变更未同步提交 → 退出非零（本地/提交前检查，尚未接入 CI）。
后端接口变更后必须重跑 `gen:api` 并提交，否则前后端类型 drift。

> **金额红线**：后端金额字段（`BigDecimal`）经 Jackson 默认序列化为 JSON **number**，生成类型即 `number`
> （仅 MCP 工具层为保精度输出 string）。这是已知精度缺口，前端拿到后必须经 `toDecimal` 转 `decimal.js`
> 参与运算，禁止 `Number()`/`parseFloat` 做金额算术——ESLint `no-restricted-syntax` 硬拦（见 `frontend/eslint.config.js`）。
