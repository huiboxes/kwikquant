package com.kwikquant.trading.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.PositionMapper;
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
public class PositionController {

    private final PositionMapper positionMapper;
    private final ExchangeAccountService accountService;

    public PositionController(PositionMapper positionMapper, ExchangeAccountService accountService) {
        this.positionMapper = positionMapper;
        this.accountService = accountService;
    }

    @GetMapping
    public ApiResponse<List<PositionDto>> list(
            @RequestParam long accountId, @RequestParam(required = false) String symbol) {
        // 鉴权：验证当前用户拥有该账户
        long currentUserId = SecurityUtils.currentUserId();
        accountService.getOwned(accountId, currentUserId);

        List<Position> positions;
        if (symbol != null && !symbol.isBlank()) {
            Position p = positionMapper.findByAccountAndSymbol(accountId, symbol);
            positions = p != null ? List.of(p) : List.of();
        } else {
            positions = positionMapper.findByAccount(accountId);
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
