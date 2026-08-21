package com.kwikquant.strategy.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.strategy.application.StrategyTemplateService;
import com.kwikquant.strategy.application.TemplateForkResult;
import com.kwikquant.strategy.domain.StrategyTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官方策略模板 REST 端点。列表/详情只读；fork 把模板复制为当前用户的 DRAFT 策略
 * （源码直接发布）并 best-effort 提交首次回测。
 *
 * <p>路径 {@code /api/v1/strategies/templates} 与 {@code /strategies/{id}} 并存：
 * Spring 路径匹配字面量段优先于路径变量段（同 {@code /strategies/last-edited} 先例）。
 */
@RestController
@RequestMapping("/api/v1/strategies/templates")
@Tag(name = "策略模板")
class StrategyTemplateController {

    private final StrategyTemplateService templateService;

    StrategyTemplateController(StrategyTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    @Operation(summary = "官方模板列表", description = "需 JWT 鉴权。返回全部官方模板元数据（不含源码，详情端点取）。")
    public ApiResponse<List<TemplateDto>> list() {
        return ApiResponse.ok(
                templateService.list().stream().map(TemplateDto::from).toList());
    }

    @GetMapping("/{key}")
    @Operation(summary = "模板详情", description = "需 JWT 鉴权。含模板源码与默认参数。模板 key 不存在返回 404（7008）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "模板不存在（7008 TEMPLATE_NOT_FOUND）")
    public ApiResponse<TemplateDetailDto> get(
            @Parameter(description = "模板 key", example = "ma-double-cross") @PathVariable String key) {
        return ApiResponse.ok(TemplateDetailDto.from(templateService.require(key)));
    }

    @PostMapping("/{key}/fork")
    @Operation(
            summary = "fork 模板为我的策略",
            description = "需 JWT 鉴权。复制模板为当前用户 DRAFT 策略并直接发布源码，随后 best-effort 提交首次回测（模板推荐窗口）。"
                    + "回测提交失败（配额满/worker 不可用等）不回滚 fork，firstBacktestTaskId 为 null 且 backtestSkipReason 给出原因。"
                    + "模板 key 不存在返回 404（7008）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "模板不存在（7008 TEMPLATE_NOT_FOUND）")
    public ApiResponse<TemplateForkResultDto> fork(
            @Parameter(description = "模板 key", example = "ma-double-cross") @PathVariable String key) {
        TemplateForkResult result = templateService.fork(key, SecurityUtils.currentUserId());
        return ApiResponse.ok(TemplateForkResultDto.from(result));
    }

    /** 模板列表项（不含源码/参数，控 payload）。 */
    record TemplateDto(
            @Schema(description = "模板 key（fork/详情用）", example = "ma-double-cross") String key,
            @Schema(description = "模板名称", example = "均线双金叉") String name,
            @Schema(description = "模板描述") String description,
            @Schema(description = "标签（行情风格分类）", example = "[\"趋势跟踪\"]") List<String> tags,
            @Schema(description = "推荐交易对", example = "BTC/USDT") String symbol,
            @Schema(description = "推荐交易所", example = "OKX") String exchange,
            @Schema(description = "推荐 K 线周期", example = "1h") String intervalValue,
            @Schema(description = "推荐首次回测窗口（天）", example = "90") int backtestWindowDays) {
        static TemplateDto from(StrategyTemplate t) {
            return new TemplateDto(
                    t.key(),
                    t.name(),
                    t.description(),
                    t.tags(),
                    t.symbol(),
                    t.exchange(),
                    t.intervalValue(),
                    t.backtestWindowDays());
        }
    }

    /** 模板详情（含源码与默认参数，代码预览/二次开发用）。 */
    record TemplateDetailDto(
            @Schema(description = "模板 key", example = "ma-double-cross") String key,
            @Schema(description = "模板名称", example = "均线双金叉") String name,
            @Schema(description = "模板描述") String description,
            @Schema(description = "标签", example = "[\"趋势跟踪\"]") List<String> tags,
            @Schema(description = "推荐交易对", example = "BTC/USDT") String symbol,
            @Schema(description = "推荐交易所", example = "OKX") String exchange,
            @Schema(description = "推荐 K 线周期", example = "1h") String intervalValue,
            @Schema(description = "推荐首次回测窗口（天）", example = "90") int backtestWindowDays,
            @Schema(description = "默认策略参数（JSON 字符串）", example = "{}") String parameters,
            @Schema(description = "模板源码（函数式 on_bar）") String sourceCode) {
        static TemplateDetailDto from(StrategyTemplate t) {
            return new TemplateDetailDto(
                    t.key(),
                    t.name(),
                    t.description(),
                    t.tags(),
                    t.symbol(),
                    t.exchange(),
                    t.intervalValue(),
                    t.backtestWindowDays(),
                    t.parameters(),
                    t.sourceCode());
        }
    }

    /** fork 结果：新策略详情 + 首回测任务 ID（null = 未提交，原因见 backtestSkipReason）。 */
    record TemplateForkResultDto(
            @Schema(description = "fork 出的策略详情（DRAFT，源码已发布）") StrategyController.StrategyDetailDto strategy,
            @Schema(description = "自动提交的首次回测任务 ID；未提交为 null", example = "77", nullable = true) Long firstBacktestTaskId,
            @Schema(description = "首回测未提交时的用户可读原因；已提交为 null", nullable = true) String backtestSkipReason) {
        static TemplateForkResultDto from(TemplateForkResult r) {
            return new TemplateForkResultDto(
                    StrategyController.StrategyDetailDto.from(r.strategy()),
                    r.firstBacktestTaskId(),
                    r.backtestSkipReason());
        }
    }
}
