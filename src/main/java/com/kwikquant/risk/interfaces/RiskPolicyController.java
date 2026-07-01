package com.kwikquant.risk.interfaces;

import com.kwikquant.risk.application.RiskPolicyManagementService;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
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
    public ApiResponse<RiskPolicyDto> create(@RequestBody @Valid RiskPolicyRequest req) {
        long currentUserId = SecurityUtils.currentUserId();
        RiskRuleType ruleType = parseRuleType(req.ruleType());
        RiskPolicy policy = managementService.create(
                req.accountId(), currentUserId, ruleType, req.name(), req.params());
        log.info("Created risk policy id={} for accountId={}", policy.getId(), req.accountId());
        return ApiResponse.ok(RiskPolicyDto.from(policy), null);
    }

    /**
     * Lists all risk policies for an account.
     *
     * @param accountId the exchange account id
     * @return list of policies
     */
    @GetMapping
    public ApiResponse<List<RiskPolicyDto>> list(@RequestParam long accountId) {
        long currentUserId = SecurityUtils.currentUserId();
        List<RiskPolicy> policies = managementService.listByAccount(accountId, currentUserId);
        List<RiskPolicyDto> dtos = policies.stream().map(RiskPolicyDto::from).toList();
        return ApiResponse.ok(dtos, null);
    }

    /**
     * Updates a risk policy's name and parameters.
     *
     * @param policyId the policy id
     * @param req      the update request
     * @return the updated policy
     */
    @PutMapping("/{policyId}")
    public ApiResponse<RiskPolicyDto> update(
            @PathVariable long policyId, @RequestBody @Valid RiskPolicyRequest req) {
        long currentUserId = SecurityUtils.currentUserId();
        RiskPolicy policy = managementService.update(policyId, currentUserId, req.name(), req.params());
        return ApiResponse.ok(RiskPolicyDto.from(policy), null);
    }

    /**
     * Toggles a risk policy's enabled state.
     *
     * @param policyId the policy id
     * @param req      the toggle request
     * @return the updated policy
     */
    @PatchMapping("/{policyId}/toggle")
    public ApiResponse<RiskPolicyDto> toggle(
            @PathVariable long policyId, @RequestBody @Valid ToggleRequest req) {
        long currentUserId = SecurityUtils.currentUserId();
        RiskPolicy policy = managementService.toggle(policyId, currentUserId, req.enabled());
        return ApiResponse.ok(RiskPolicyDto.from(policy), null);
    }

    /**
     * Deletes a risk policy.
     *
     * @param policyId the policy id
     */
    @DeleteMapping("/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long policyId) {
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
