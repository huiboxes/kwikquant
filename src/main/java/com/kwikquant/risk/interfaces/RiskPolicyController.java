package com.kwikquant.risk.interfaces;

import com.kwikquant.risk.application.RiskPolicyManagementService;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Risk policy CRUD REST API.
 *
 * <p>All mutating operations require the current user to own the associated exchange account.
 * Ownership is enforced by {@link RiskPolicyManagementService}.
 */
@RestController
@RequestMapping("/api/v1/risk/policies")
@Tag(name = "风控策略")
public class RiskPolicyController {

    private static final Logger log = LoggerFactory.getLogger(RiskPolicyController.class);

    private final RiskPolicyManagementService managementService;

    public RiskPolicyController(RiskPolicyManagementService managementService) {
        this.managementService = managementService;
    }

    /**
     * Creates a new risk policy.
     *
     * @param req the creation request
     * @return the created policy
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "创建风控策略",
            description = "需 JWT 鉴权。同一账户同 ruleType 的策略 scope 不可重叠，重叠返回 409（2011）。" + "ruleType/params 非法返回 400（3001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "策略冲突，同账户同 ruleType scope 重叠（2011 RISK_POLICY_CONFLICT）")
    public ApiResponse<RiskPolicyDto> create(@RequestBody @Valid RiskPolicyRequest req) {
        long currentUserId = SecurityUtils.currentUserId();
        RiskRuleType ruleType = parseRuleType(req.ruleType());
        RiskPolicy policy =
                managementService.create(req.accountId(), currentUserId, ruleType, req.name(), req.params());
        log.info("Created risk policy id={} for accountId={}", policy.getId(), req.accountId());
        return ApiResponse.ok(RiskPolicyDto.from(policy));
    }

    /**
     * Lists all risk policies for an account.
     *
     * @param accountId the exchange account id
     * @return list of policies
     */
    @GetMapping
    @Operation(summary = "查询账户风控策略列表", description = "需 JWT 鉴权。仅返回当前用户拥有账户的策略，越权返回 403（1002）。")
    public ApiResponse<List<RiskPolicyDto>> list(
            @Parameter(description = "账户 ID", example = "7") @RequestParam long accountId) {
        long currentUserId = SecurityUtils.currentUserId();
        List<RiskPolicy> policies = managementService.listByAccount(accountId, currentUserId);
        List<RiskPolicyDto> dtos = policies.stream().map(RiskPolicyDto::from).toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * Updates a risk policy's name and parameters.
     *
     * @param policyId the policy id
     * @param req      the update request
     * @return the updated policy
     */
    @PutMapping("/{policyId}")
    @Operation(summary = "更新风控策略", description = "需 JWT 鉴权。可更新 name + params。策略不存在或非本人返回 409（4009）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（2010 RISK_POLICY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "策略状态冲突，不存在或非本人（4009 STATE_CONFLICT）")
    public ApiResponse<RiskPolicyDto> update(
            @Parameter(description = "策略 ID", example = "42") @PathVariable long policyId,
            @RequestBody @Valid RiskPolicyRequest req) {
        long currentUserId = SecurityUtils.currentUserId();
        RiskPolicy policy = managementService.update(policyId, currentUserId, req.name(), req.params());
        return ApiResponse.ok(RiskPolicyDto.from(policy));
    }

    /**
     * Toggles a risk policy's enabled state.
     *
     * @param policyId the policy id
     * @param req      the toggle request
     * @return the updated policy
     */
    @PatchMapping("/{policyId}/toggle")
    @Operation(summary = "启停风控策略", description = "需 JWT 鉴权。false 表示策略存在但不生效。策略不存在或非本人返回 409（4009）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（2010 RISK_POLICY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "策略状态冲突，不存在或非本人（4009 STATE_CONFLICT）")
    public ApiResponse<RiskPolicyDto> toggle(
            @Parameter(description = "策略 ID", example = "42") @PathVariable long policyId,
            @RequestBody @Valid ToggleRequest req) {
        long currentUserId = SecurityUtils.currentUserId();
        RiskPolicy policy = managementService.toggle(policyId, currentUserId, req.enabled());
        return ApiResponse.ok(RiskPolicyDto.from(policy));
    }

    /**
     * Deletes a risk policy.
     *
     * @param policyId the policy id
     */
    @DeleteMapping("/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除风控策略", description = "需 JWT 鉴权。返回 204 NO_CONTENT。策略不存在或非本人返回 409（4009）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（2010 RISK_POLICY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "策略状态冲突，不存在或非本人（4009 STATE_CONFLICT）")
    public void delete(@Parameter(description = "策略 ID", example = "42") @PathVariable long policyId) {
        long currentUserId = SecurityUtils.currentUserId();
        managementService.delete(policyId, currentUserId);
    }

    private static RiskRuleType parseRuleType(String ruleType) {
        try {
            return RiskRuleType.valueOf(ruleType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid rule type: " + ruleType);
        }
    }
}
