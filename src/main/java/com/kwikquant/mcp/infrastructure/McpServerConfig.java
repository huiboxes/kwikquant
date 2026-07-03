package com.kwikquant.mcp.infrastructure;

import org.springframework.context.annotation.Configuration;

/**
 * MCP server 配置（§3.2）。
 *
 * <p>Spring AI starter {@code spring-ai-starter-mcp-server-webmvc} 自动配置 MCP server：扫描
 * {@code @McpTool} 注解方法注册工具，暴露 {@code POST /mcp} Streamable-HTTP 端点。server name/version/protocol
 * 由 {@code application.yaml} 的 {@code spring.ai.mcp.server.*} 提供，本类不重复声明，仅在后续需要定制
 * server 行为时作为扩展点。
 *
 * <p>工具 bean（{@code MarketDataTools}/{@code TradingTools}/{@code StrategyTools}/{@code AccountTools}/
 * {@code RiskTools}）在 Step 3-7 才创建，本 Step 验证 {@code /mcp} 端点就绪 + {@code tools/list} 返 0 工具。
 *
 * <p>JaCoCo 按惯例排除（纯配置类，无业务逻辑）。
 */
@Configuration
public class McpServerConfig {}
