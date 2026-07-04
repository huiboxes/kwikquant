package com.kwikquant.risk.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.infrastructure.RiskDecisionMapper;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.PageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Risk decision query REST API.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/v1/risk/decisions?orderId={orderId} — single decision by order</li>
 *   <li>GET /api/v1/risk/decisions?accountId={accountId} — paginated list with optional filters</li>
 * </ul>
 *
 * <p>Ownership verified via ExchangeAccountService.getOwned. Returns 404 for unauthorized access
 * to prevent probing.
 */
@RestController
@RequestMapping("/api/v1/risk/decisions")
@Tag(name = "风控决策审计")
public class RiskDecisionController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final RiskDecisionMapper decisionMapper;
    private final ExchangeAccountService exchangeAccountService;

    public RiskDecisionController(RiskDecisionMapper decisionMapper, ExchangeAccountService exchangeAccountService) {
        this.decisionMapper = decisionMapper;
        this.exchangeAccountService = exchangeAccountService;
    }

    /**
     * Single decision lookup by orderId. Ownership verified via decision.accountId.
     */
    @GetMapping(params = "orderId")
    @Operation(summary = "按订单查风控决策", description = "需 JWT 鉴权。越权访问他人订单返回 404（防探测，不返回 403）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "决策不存在或不属于当前用户（4001 RESOURCE_NOT_FOUND，越权也返 404 防探测）")
    public ApiResponse<RiskDecisionDto> getByOrderId(
            @Parameter(description = "订单 ID", example = "1024") @RequestParam long orderId) {
        long currentUserId = SecurityUtils.currentUserId();

        RiskDecision decision = decisionMapper.findByOrderId(orderId);
        if (decision == null) {
            throw new ResourceNotFoundException("risk decision for orderId " + orderId);
        }

        verifyOwnership(decision.getAccountId(), currentUserId, orderId);
        return ApiResponse.ok(RiskDecisionDto.from(decision));
    }

    /**
     * Paginated risk decision listing by account with optional filters.
     *
     * @param accountId required — the exchange account to query
     * @param verdict   optional — filter by APPROVED or REJECTED
     * @param startTime optional — lower bound on created_at (ISO-8601)
     * @param endTime   optional — upper bound on created_at (ISO-8601)
     * @param page      page number (1-based, default 1)
     * @param pageSize  page size (default 50, max 200)
     */
    @GetMapping(params = "accountId")
    @Operation(
            summary = "分页查询账户风控决策",
            description = "需 JWT 鉴权。按账户 + 可选 verdict/时间范围分页查询。越权访问他人账户返回 403（1002）。"
                    + "verdict=REJECTED 的决策 data 字段含 2001 RISK_REJECTED 业务码（非 HTTP 响应码）。")
    public ApiResponse<PageDto<RiskDecisionDto>> listByAccount(
            @Parameter(description = "账户 ID，必填", example = "7") @RequestParam long accountId,
            @Parameter(description = "按 verdict 过滤（枚举: APPROVED | REJECTED）", example = "REJECTED")
                    @RequestParam(required = false)
                    String verdict,
            @Parameter(description = "created_at 下限 ISO-8601", example = "2026-07-01T00:00:00Z")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant startTime,
            @Parameter(description = "created_at 上限 ISO-8601", example = "2026-07-04T00:00:00Z")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant endTime,
            @Parameter(description = "页码，1-based，默认 1", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数，默认 50，最大 200", example = "50")
                    @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE)
                    int pageSize) {
        long currentUserId = SecurityUtils.currentUserId();
        exchangeAccountService.getOwned(accountId, currentUserId);

        int effectivePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 1);
        int offset = (effectivePage - 1) * effectivePageSize;

        // Normalize verdict to uppercase enum name if provided
        String normalizedVerdict =
                (verdict != null && !verdict.isBlank()) ? verdict.trim().toUpperCase() : null;

        List<RiskDecision> decisions = decisionMapper.findByAccount(
                accountId, normalizedVerdict, startTime, endTime, effectivePageSize, offset);
        long total = decisionMapper.countByAccount(accountId, normalizedVerdict, startTime, endTime);

        List<RiskDecisionDto> dtos =
                decisions.stream().map(RiskDecisionDto::from).toList();
        PageDto<RiskDecisionDto> pageDto = PageDto.of(dtos, effectivePage, effectivePageSize, total);
        return ApiResponse.ok(pageDto);
    }

    /** Verify ownership; throw 404 (not 403) to prevent orderId existence probing. */
    private void verifyOwnership(long accountId, long currentUserId, long orderId) {
        try {
            exchangeAccountService.getOwned(accountId, currentUserId);
        } catch (RuntimeException e) {
            throw new ResourceNotFoundException("risk decision for orderId " + orderId);
        }
    }
}
