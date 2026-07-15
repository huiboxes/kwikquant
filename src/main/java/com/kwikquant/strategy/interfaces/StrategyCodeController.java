package com.kwikquant.strategy.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.strategy.application.StrategyCodeService;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyCodeStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@Tag(name = "策略代码版本")
class StrategyCodeController {

    private final StrategyCodeService codeService;

    StrategyCodeController(StrategyCodeService codeService) {
        this.codeService = codeService;
    }

    @PostMapping
    @Operation(
            summary = "创建代码草稿",
            description = "需 JWT 鉴权。为策略创建 DRAFT 状态的代码版本。已有未发布 DRAFT 返回 409（7005）；" + "sourceCode 超 1MB 返回 400（3001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "已有未发布 DRAFT，不可重复创建（7005 STRATEGY_CODE_ILLEGAL_STATE）")
    public ApiResponse<StrategyCodeDto> createDraft(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long strategyId,
            @Valid @RequestBody CreateCodeRequest req) {
        StrategyCode code =
                codeService.createDraft(strategyId, SecurityUtils.currentUserId(), req.sourceCode(), req.changelog());
        return ApiResponse.ok(StrategyCodeDto.from(code));
    }

    @GetMapping
    @Operation(summary = "查询策略代码版本列表", description = "需 JWT 鉴权。按版本号倒序返回，不含 sourceCode 正文。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    public ApiResponse<List<StrategyCodeDto>> list(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long strategyId) {
        return ApiResponse.ok(codeService.listByStrategy(strategyId, SecurityUtils.currentUserId()).stream()
                .map(StrategyCodeDto::from)
                .toList());
    }

    @GetMapping("/{codeId}")
    @Operation(
            summary = "查代码版本详情",
            description = "需 JWT 鉴权。返回含 sourceCode 正文（list 端点不含 sourceCode，前端 Monaco reload 草稿走此端点，契约改动 A）。"
                    + "代码不存在/非本人返回 404（7004）；策略不存在返回 404（7001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略或代码不存在（7001 STRATEGY_NOT_FOUND / 7004 STRATEGY_CODE_NOT_FOUND）")
    public ApiResponse<StrategyCodeDetailDto> get(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long strategyId,
            @Parameter(description = "代码版本 ID", example = "256") @PathVariable long codeId) {
        StrategyCode code = codeService.getOwnedCode(strategyId, SecurityUtils.currentUserId(), codeId);
        return ApiResponse.ok(StrategyCodeDetailDto.from(code));
    }

    @PutMapping("/{codeId}")
    @Operation(
            summary = "更新代码草稿",
            description = "需 JWT 鉴权。仅 DRAFT 状态可改；发布后冻结，新版本走新 codeId。" + "代码不存在返回 404（7004）；非 DRAFT 返回 409（7005）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "策略或代码不存在（7001/7004）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "代码非 DRAFT 不可改（7005）或不存在/非本人（4009）")
    public ApiResponse<StrategyCodeDto> updateDraft(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long strategyId,
            @Parameter(description = "代码版本 ID", example = "256") @PathVariable long codeId,
            @Valid @RequestBody UpdateCodeRequest req) {
        StrategyCode code = codeService.updateDraft(
                strategyId, SecurityUtils.currentUserId(), codeId, req.sourceCode(), req.changelog());
        return ApiResponse.ok(StrategyCodeDto.from(code));
    }

    @PostMapping("/{codeId}/publish")
    @Operation(
            summary = "发布代码版本",
            description =
                    "需 JWT 鉴权。DRAFT→PUBLISHED 转移，发布后冻结不可改，新版本走新 codeId。" + "代码不存在返回 404（7004）；非 DRAFT 返回 409（7005）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "策略或代码不存在（7001/7004）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "代码非 DRAFT 不可发布（7005）或不存在/非本人（4009）")
    public ApiResponse<StrategyCodeDto> publish(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long strategyId,
            @Parameter(description = "代码版本 ID", example = "256") @PathVariable long codeId) {
        StrategyCode code = codeService.publish(strategyId, SecurityUtils.currentUserId(), codeId);
        return ApiResponse.ok(StrategyCodeDto.from(code));
    }

    @DeleteMapping("/{codeId}")
    @Operation(
            summary = "删除代码草稿",
            description = "需 JWT 鉴权。仅 DRAFT 可删(放弃当前未发布草稿);PUBLISHED/ARCHIVED 不可删返 409(7005)。代码不存在返 404(7004)。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "策略或代码不存在（7001/7004）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "代码非 DRAFT 不可删（7005）或不存在/非本人（4009）")
    public ApiResponse<Void> deleteCode(
            @Parameter(description = "策略 ID", example = "128") @PathVariable long strategyId,
            @Parameter(description = "代码版本 ID", example = "256") @PathVariable long codeId) {
        codeService.deleteCode(strategyId, SecurityUtils.currentUserId(), codeId);
        return ApiResponse.ok(null);
    }

    record CreateCodeRequest(
            @Schema(
                            description = "策略源代码（Python），≤1MB",
                            example = "def on_tick(ctx): ...",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(max = 1_000_000)
                    String sourceCode,
            @Schema(description = "变更日志，≤2000 字符", example = "新增网格逻辑") @Size(max = 2000) String changelog) {}

    record UpdateCodeRequest(
            @Schema(
                            description = "策略源代码（Python），≤1MB",
                            example = "def on_tick(ctx): ...",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Size(max = 1_000_000)
                    String sourceCode,
            @Schema(description = "变更日志", example = "修复撮合 bug") @Size(max = 2000) String changelog) {}

    record StrategyCodeDto(
            @Schema(description = "代码版本 ID", example = "256") Long id,
            @Schema(description = "所属策略 ID", example = "128") long strategyId,
            @Schema(description = "版本号，递增", example = "3") int versionNumber,
            @Schema(description = "代码状态（枚举: DRAFT | PUBLISHED）", example = "PUBLISHED") StrategyCodeStatus status,
            @Schema(description = "语言", example = "python") String language,
            @Schema(description = "变更日志") String changelog,
            @Schema(description = "创建时间", example = "2026-07-04T12:00:00Z") Instant createdAt,
            @Schema(description = "最后更新时间", example = "2026-07-04T12:00:00Z") Instant updatedAt) {
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

    /**
     * 代码版本详情（含 sourceCode 正文）。list 端点返 {@link StrategyCodeDto}（不含 sourceCode），
     * GET /{codeId} 返此 DTO（契约改动 A：前端 Monaco reload 草稿刚需）。
     */
    record StrategyCodeDetailDto(
            @Schema(description = "代码版本 ID", example = "256") Long id,
            @Schema(description = "所属策略 ID", example = "128") long strategyId,
            @Schema(description = "版本号，递增", example = "3") int versionNumber,
            @Schema(description = "代码状态（枚举: DRAFT | PUBLISHED）", example = "DRAFT") StrategyCodeStatus status,
            @Schema(description = "语言", example = "python") String language,
            @Schema(description = "变更日志") String changelog,
            @Schema(description = "源代码正文（Python），≤1MB", example = "def on_tick(ctx): ...") String sourceCode,
            @Schema(description = "创建时间", example = "2026-07-04T12:00:00Z") Instant createdAt,
            @Schema(description = "最后更新时间", example = "2026-07-04T12:00:00Z") Instant updatedAt) {
        static StrategyCodeDetailDto from(StrategyCode c) {
            return new StrategyCodeDetailDto(
                    c.getId(),
                    c.getStrategyId(),
                    c.getVersionNumber(),
                    c.getStatus(),
                    c.getLanguage(),
                    c.getChangelog(),
                    c.getSourceCode(),
                    c.getCreatedAt(),
                    c.getUpdatedAt());
        }
    }
}
