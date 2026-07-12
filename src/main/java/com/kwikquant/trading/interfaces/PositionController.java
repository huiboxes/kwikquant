package com.kwikquant.trading.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.trading.application.PositionService;
import com.kwikquant.trading.domain.Position;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 持仓 REST API。
 *
 * <p>端点：GET /api/v1/positions?accountId={accountId}&symbol={symbol}（可选）。
 */
@RestController
@RequestMapping("/api/v1/positions")
@Tag(name = "持仓")
public class PositionController {

    private final PositionService positionService;
    private final ExchangeAccountService accountService;

    public PositionController(PositionService positionService, ExchangeAccountService accountService) {
        this.positionService = positionService;
        this.accountService = accountService;
    }

    @GetMapping
    @Operation(summary = "查询持仓", description = "需 JWT 鉴权。按账户 + 可选 symbol 返回持仓列表。后端校验账户归属，越权返回 403（1002）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "越权访问他人账户（1002 FORBIDDEN）")
    public ApiResponse<List<PositionDto>> list(
            @Parameter(description = "账户 ID，鉴权校验归属", example = "42") @RequestParam long accountId,
            @Parameter(description = "按 canonical symbol 过滤，为空则返回该账户全部持仓", example = "BTC/USDT")
                    @RequestParam(required = false)
                    String symbol) {
        // 鉴权：验证当前用户拥有该账户
        long currentUserId = SecurityUtils.currentUserId();
        accountService.getOwned(accountId, currentUserId);

        List<Position> positions;
        if (symbol != null && !symbol.isBlank()) {
            Position p = positionService.findByAccountAndSymbol(accountId, symbol);
            positions = p != null ? List.of(p) : List.of();
        } else {
            positions = positionService.findByAccount(accountId);
        }
        List<PositionDto> dtos = positions.stream().map(this::toDto).toList();
        return ApiResponse.ok(dtos);
    }

    private PositionDto toDto(Position position) {
        return new PositionDto(
                position.getId(),
                position.getAccountId(),
                position.getSymbol(),
                position.getSide(),
                position.getQty(),
                position.getAvgEntryPrice(),
                position.getRealizedPnl(),
                position.getVersion(),
                position.getUpdatedAt());
    }
}
