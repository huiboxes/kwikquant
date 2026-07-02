package com.kwikquant.strategy.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.strategy.application.StrategyCodeService;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyCodeStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 策略代码版本管理 REST 端点。sourceCode ≤1MB（spec-review S-3，服务层二次校验）。
 */
@RestController
@RequestMapping("/api/v1/strategies/{strategyId}/codes")
class StrategyCodeController {

    private final StrategyCodeService codeService;

    StrategyCodeController(StrategyCodeService codeService) {
        this.codeService = codeService;
    }

    @PostMapping
    public ApiResponse<StrategyCodeDto> createDraft(
            @PathVariable long strategyId, @Valid @RequestBody CreateCodeRequest req) {
        StrategyCode code =
                codeService.createDraft(strategyId, SecurityUtils.currentUserId(), req.sourceCode(), req.changelog());
        return ApiResponse.ok(StrategyCodeDto.from(code));
    }

    @GetMapping
    public ApiResponse<List<StrategyCodeDto>> list(@PathVariable long strategyId) {
        return ApiResponse.ok(codeService.listByStrategy(strategyId, SecurityUtils.currentUserId()).stream()
                .map(StrategyCodeDto::from)
                .toList());
    }

    @PutMapping("/{codeId}")
    public ApiResponse<StrategyCodeDto> updateDraft(
            @PathVariable long strategyId, @PathVariable long codeId, @Valid @RequestBody UpdateCodeRequest req) {
        StrategyCode code = codeService.updateDraft(
                strategyId, SecurityUtils.currentUserId(), codeId, req.sourceCode(), req.changelog());
        return ApiResponse.ok(StrategyCodeDto.from(code));
    }

    @PostMapping("/{codeId}/publish")
    public ApiResponse<StrategyCodeDto> publish(@PathVariable long strategyId, @PathVariable long codeId) {
        StrategyCode code = codeService.publish(strategyId, SecurityUtils.currentUserId(), codeId);
        return ApiResponse.ok(StrategyCodeDto.from(code));
    }

    record CreateCodeRequest(@NotBlank @Size(max = 1_000_000) String sourceCode, @Size(max = 2000) String changelog) {}

    record UpdateCodeRequest(@NotBlank @Size(max = 1_000_000) String sourceCode, @Size(max = 2000) String changelog) {}

    record StrategyCodeDto(
            Long id,
            long strategyId,
            int versionNumber,
            StrategyCodeStatus status,
            String language,
            String changelog,
            Instant createdAt,
            Instant updatedAt) {
        static StrategyCodeDto from(StrategyCode c) {
            return new StrategyCodeDto(
                    c.getId(),
                    c.getStrategyId(),
                    c.getVersionNumber(),
                    c.getStatus(),
                    c.getLanguage(),
                    c.getChangelog(),
                    c.getCreatedAt(),
                    c.getUpdatedAt());
        }
    }
}
