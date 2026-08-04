package com.kwikquant.risk.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.risk.application.RiskDecisionQueryService;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.shared.types.PageQuery;
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

    private final RiskDecisionQueryService queryService;
    private final ExchangeAccountService exchangeAccountService;

    public RiskDecisionController(
            RiskDecisionQueryService queryService, ExchangeAccountService exchangeAccountService) {
        this.queryService = queryService;
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

        RiskDecision decision = queryService.findByOrderId(orderId);
        if (decision == null) {
            throw new ResourceNotFoundException("risk decision for orderId " + orderId);
        }

        verifyOwnership(decision.getAccountId(), currentUserId, orderId);
        return ApiResponse.ok(RiskDecisionDto.from(decision));
    }

    /**
     * Paginated risk decision listing, optionally filtered by account / verdict / time range.
     *
     * <p>{@code accountId} 省略时跨账户返当前用户所有账户的决策(风控页总览用,对应原型
     * {@code RiskPage.jsx} 的 {@code data.riskAudit});非空则按账户过滤并校验归属(越权 403)。
     * {@code params = "!orderId"} 与 {@link #getByOrderId}({@code params = "orderId"})显式互斥——
     * 请求带 orderId 走按订单查单条,不带走分页列表,避免 Spring MVC 歧义映射。
     *
     * @param accountId optional — the exchange account to query; null = cross-account
     * @param verdict   optional — filter by APPROVED or REJECTED
     * @param startTime optional — lower bound on created_at (ISO-8601)
     * @param endTime   optional — upper bound on created_at (ISO-8601)
     * @param page      page number (1-based, default 1)
     * @param pageSize  page size (default 50, max 200)
     */
    @GetMapping(params = "!orderId")
    @Operation(
            summary = "分页查询风控决策",
            description = "需 JWT 鉴权。accountId 省略时跨账户返当前用户所有账户决策(风控页总览用);"
                    + "非空则按账户过滤(越权返 403 1002)。可选 verdict/时间范围。"
                    + "verdict=REJECTED 的决策 data 字段含 2001 RISK_REJECTED 业务码(非 HTTP 响应码)。")
    public ApiResponse<PageDto<RiskDecisionDto>> list(
            @Parameter(description = "账户 ID,可省略(省略跨账户查当前用户全部)", example = "7") @RequestParam(required = false)
                    Long accountId,
            @Parameter(description = "按 verdict 过滤(枚举: APPROVED | REJECTED)", example = "REJECTED")
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
            @Parameter(description = "页码,1-based,默认 1", example = "1") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数,默认 50,最大 200", example = "50") @RequestParam(required = false)
                    Integer pageSize) {
        long currentUserId = SecurityUtils.currentUserId();

        PageQuery pq = PageQuery.ofLarge(page, pageSize);

        // Normalize verdict to uppercase enum name if provided
        String normalizedVerdict =
                (verdict != null && !verdict.isBlank()) ? verdict.trim().toUpperCase() : null;

        List<RiskDecision> decisions;
        long total;
        if (accountId != null) {
            exchangeAccountService.getOwned(accountId, currentUserId);
            decisions = queryService.findByAccount(
                    accountId, normalizedVerdict, startTime, endTime, pq.pageSize(), pq.offset());
            total = queryService.countByAccount(accountId, normalizedVerdict, startTime, endTime);
        } else {
            decisions = queryService.findByUserId(
                    currentUserId, normalizedVerdict, startTime, endTime, pq.pageSize(), pq.offset());
            total = queryService.countByUserId(currentUserId, normalizedVerdict, startTime, endTime);
        }

        List<RiskDecisionDto> dtos =
                decisions.stream().map(RiskDecisionDto::from).toList();
        PageDto<RiskDecisionDto> pageDto = PageDto.of(dtos, pq.page(), pq.pageSize(), total);
        return ApiResponse.ok(pageDto);
    }

    /** Verify ownership; throw 404 (not 403) to prevent orderId existence probing. */
    private void verifyOwnership(long accountId, long currentUserId, long orderId) {
        try {
            exchangeAccountService.getOwned(accountId, currentUserId);
        } catch (ResourceNotFoundException | OwnershipViolationException e) {
            throw new ResourceNotFoundException("risk decision for orderId " + orderId);
        }
    }
}
