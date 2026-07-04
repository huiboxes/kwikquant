# src/types

后端契约生成的类型落点。

- `api-gen.ts` —— 由 `pnpm gen:api` 从后端 OpenAPI 契约（`/v3/api-docs`）生成，**禁止手写/手改**。生成物已列入 `.gitignore`，不进 git（drift 走 CI 校验）。
- `src/api/` 层负责把生成类型翻译为前端领域模型（业务阶段规范）。

## 生成

```bash
# 后端需在 8080 运行（dev profile，prod 已关闭 api-docs）
pnpm gen:api
# 自定义地址：
KWIKQUANT_API_DOCS=http://host:port/v3/api-docs pnpm gen:api
```

## CI drift 守护

`pnpm gen:api:check` 重新生成并 `git diff --exit-code`：契约变更未同步提交 → CI 失败。
后端接口契约改动后必须重跑 `gen:api` 并提交，否则前后端类型 drift。

> **P0 金额红线**：后端金额字段（`BigDecimal`）契约序列化为 **string**，生成类型即为 `string`，
> 前端必须用 `decimal.js` 接收，禁止 `Number()`/`parseFloat` 参与金额运算。
> 这条通过 ESLint `no-restricted-syntax` 硬拦（见 Step 3 `eslint.config.js`）。
