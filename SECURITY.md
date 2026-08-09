# 安全策略

## 报告漏洞

发现安全漏洞请**不要**开公开 issue,优先通过 GitHub Private Vulnerability Reporting 提交,或私邮到维护者。会在 72 小时内响应。

## 主动安全设计

- **JWT 鉴权**:access + refresh token,`JwtProvider` 签发,`JwtAuthenticationFilter` 守所有敏感端点
- **API key 加密**:交易所 API key 用 AES-256-GCM 加密落库(`ApiKeyEncryptor`),密钥从环境变量注入,不落库
- **所有权检查**:`OwnershipCheck` 工具强制用户只能访问自己的资源(账户 / 订单 / 策略)
- **WebSocket 鉴权**:`WebSocketAuthInterceptor` 守 WS 连接
- **MCP PAT**:MCP server 用 PAT(Personal Access Token)+ HMAC pepper 鉴权,不走 JWT
- **proxy 隔离**:JVM 用 `nonProxyHosts` 白名单本地地址,避免本地连接被 socks proxy 劫持

## 支持版本

| 版本 | 支持 |
|---|---|
| main(即将上线) | ✅ |
| 旧 wave 分支 | ❌(已清理) |
