package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.account.application.ExchangeAccountService.ExchangeAccountView;
import com.kwikquant.shared.types.Exchange;

/**
 * MCP {@code list_accounts} 工具返回的账户投影。<b>剥离 apiKey 明文</b>（{@link ExchangeAccountView#apiKey()}
 * 含末 4 位标识，不应泄露给 Agent）。只暴露 id/exchange/label/paperTrading/status。
 */
public record McpExchangeAccountView(Long id, Exchange exchange, String label, boolean paperTrading, String status) {
    public static McpExchangeAccountView from(ExchangeAccountView v) {
        return new McpExchangeAccountView(v.id(), v.exchange(), v.label(), v.paperTrading(), v.status());
    }
}
