package com.kwikquant.risk.interfaces;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.infrastructure.RiskDecisionMapper;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.PageDto;
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
public class RiskDecisionController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final RiskDecisionMapper decisionMapper;
    private final ExchangeAccountService exchangeAccountService;

    public RiskDecisionController(
            RiskDecisionMapper decisionMapper,
            ExchangeAccountService exchangeAccountService) {
        this.decisionMapper = decisionMapper;
        this.exchangeAccountService = exchangeAccountService;
    }

    /**
     * Single decision lookup by orderId. Ownership verified via decision.accountId.
     */
    @GetMapping(params = "orderId")
    public ApiResponse<RiskDecisionDto> getByOrderId(@RequestParam long orderId) {
        long currentUserId = SecurityUtils.currentUserId();

        RiskDecision decision = decisionMapper.findByOrderId(orderId);
        if (decision == null) {
            throw new ResourceNotFoundException("risk decision for orderId " + orderId);
        }

        verifyOwnership(decision.getAccountId(), currentUserId, orderId);
        return ApiResponse.ok(RiskDecisionDto.from(decision), null);
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
    public ApiResponse<PageDto<RiskDecisionDto>> listByAccount(
            @RequestParam long accountId,
            @RequestParam(required = false) String verdict,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize) {
        long currentUserId = SecurityUtils.currentUserId();
        exchangeAccountService.getOwned(accountId, currentUserId);

        int effectivePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int effectivePage = Math.max(page, 1);
        int offset = (effectivePage - 1) * effectivePageSize;

        // Normalize verdict to uppercase enum name if provided
        String normalizedVerdict = (verdict != null && !verdict.isBlank())
                ? verdict.trim().toUpperCase() : null;

        List<RiskDecision> decisions = decisionMapper.findByAccount(
                accountId, normalizedVerdict, startTime, endTime, effectivePageSize, offset);
        long total = decisionMapper.countByAccount(
                accountId, normalizedVerdict, startTime, endTime);

        List<RiskDecisionDto> dtos = decisions.stream().map(RiskDecisionDto::from).toList();
        PageDto<RiskDecisionDto> pageDto = PageDto.of(dtos, effectivePage, effectivePageSize, total);
        return ApiResponse.ok(pageDto, null);
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
