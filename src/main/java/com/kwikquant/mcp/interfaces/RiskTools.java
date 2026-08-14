package com.kwikquant.mcp.interfaces;

import com.kwikquant.mcp.application.McpConfirmTokenService;
import com.kwikquant.mcp.application.McpScopeGuard;
import com.kwikquant.mcp.interfaces.view.ConfirmRequiredView;
import com.kwikquant.mcp.interfaces.view.EmergencyStopPreview;
import com.kwikquant.mcp.interfaces.view.EmergencyStopView;
import com.kwikquant.mcp.interfaces.view.RiskPolicyView;
import com.kwikquant.mcp.interfaces.view.RiskRulesPreview;
import com.kwikquant.risk.application.RiskPolicyManagementService;
import com.kwikquant.risk.domain.RiskPolicy;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.infra.CriticalAuditActions;
import com.kwikquant.shared.infra.CriticalAuditException;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.McpTokenScope;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.application.StrategyCrudService;
import com.kwikquant.strategy.application.StrategyLifecycleService;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP 风控工具组。3 个 {@code @McpTool}：get_risk_rules / set_risk_rules / emergency_stop。
 *
 * <p><b>emergency_stop 是高危操作</b>（停当前用户所有 RUNNING 策略）：
 * <ol>
 *   <li>校验 {@code confirm==true}（缺抛 {@link McpEmergencyConfirmRequiredException} 10004）
 *   <li>生成 batchUuid
 *   <li><b>方法内前置显式调 {@link AuditRepository#save}（同步独立短事务，{@link
 *       com.kwikquant.shared.infra.CriticalAuditActions#CRITICAL_ACTIONS} 含 EMERGENCY_STOP）</b>——
 *       不用方法级 @Auditable（SpEL 无法引方法内 batchUuid），不加 @Transactional（与
 *       {@link StrategyLifecycleService} 的设计一致：Worker I/O 必须在事务外，Docker 调用不持 DB
 *       连接；@Transactional 包 stop 循环会致 Docker I/O 持 DB 连接 + 部分失败孤儿——容器已死但 DB 回滚说 RUNNING）
 *   <li>审计失败抛 {@link CriticalAuditException}，策略未停（未到 stop 步，fail-closed）
 *   <li>审计成功后 {@link StrategyCrudService#listByUser} 过滤 RUNNING → 逐个
 *       {@link StrategyLifecycleService#stop}（各 stop 单 CAS 原子，走其 @Auditable 落单条；
 *       部分失败不中断，返实际 stoppedCount/strategyIds）
 * </ol>
 *
 * <p>无 RUNNING 策略返 {@code stoppedCount:0}（非错误）。
 *
 * <p>set_risk_rules：新建须传 ruleType（{@code RiskRuleType.valueOf(raw.toUpperCase())}+try-catch 转 10002，
 * 无 fromString 不改枚举 YAGNI）；更新忽略 ruleType（不可改），有 policyId 走 update + 可选 toggle。
 */
@Component
public class RiskTools {

    private static final Logger log = LoggerFactory.getLogger(RiskTools.class);

    private static final String TOOL_SET_RULES = "set_risk_rules";
    private static final String TOOL_EMERGENCY_STOP = "emergency_stop";

    private final RiskPolicyManagementService policyService;
    private final StrategyCrudService strategyCrudService;
    private final StrategyLifecycleService lifecycleService;
    private final AuditRepository auditRepository;
    private final McpScopeGuard scopeGuard;
    private final McpConfirmTokenService confirmTokenService;

    public RiskTools(
            RiskPolicyManagementService policyService,
            StrategyCrudService strategyCrudService,
            StrategyLifecycleService lifecycleService,
            AuditRepository auditRepository,
            McpScopeGuard scopeGuard,
            McpConfirmTokenService confirmTokenService) {
        this.policyService = policyService;
        this.strategyCrudService = strategyCrudService;
        this.lifecycleService = lifecycleService;
        this.auditRepository = auditRepository;
        this.scopeGuard = scopeGuard;
        this.confirmTokenService = confirmTokenService;
    }

    @McpTool(
            name = "get_risk_rules",
            description = "查看风控规则. accountId 可省略(查全部), 非空则校验属当前PAT用户(否则 1002). " + "返回规则列表(ruleType/params/enabled).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public List<RiskPolicyView> getRiskRules(
            @McpToolParam(description = "账户ID(可省略查全部)", required = false) Long accountId) {
        scopeGuard.require(McpTokenScope.READ);
        long userId = SecurityUtils.currentUserId();
        List<RiskPolicy> policies =
                accountId != null ? policyService.listByAccount(accountId, userId) : policyService.listByUser(userId);
        return policies.stream().map(RiskPolicyView::from).toList();
    }

    @McpTool(
            name = "set_risk_rules",
            description = "设置风控规则(安全敏感, **两阶段确认**:不带 confirmToken 先返预览+令牌, 复述参数+令牌再执行). "
                    + "有 policyId=更新(name/params, 可选 enabled); 无 policyId=新建(须传 accountId+ruleType). "
                    + "ruleType: MAX_NOTIONAL/DAILY_LOSS_LIMIT/ORDER_FREQUENCY. 非法抛 10002.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public Object setRiskRules(
            @McpToolParam(description = "规则ID(更新时传, 新建省略)", required = false) Long policyId,
            @McpToolParam(description = "账户ID(新建必填)", required = false) Long accountId,
            @McpToolParam(
                            description = "规则类型(新建必填, 更新忽略): MAX_NOTIONAL/DAILY_LOSS_LIMIT/ORDER_FREQUENCY",
                            required = false)
                    String ruleType,
            @McpToolParam(description = "规则名称") String name,
            @McpToolParam(description = "规则参数(JSON 对象)", required = false) Map<String, String> params,
            @McpToolParam(description = "是否启用(可省略)", required = false) Boolean enabled,
            @McpToolParam(description = "两阶段确认令牌(第二阶段传第一阶段返回值)", required = false) String confirmToken) {
        scopeGuard.require(McpTokenScope.RISK);
        long userId = SecurityUtils.currentUserId();
        // 第一阶段前快速失败:非法入参直接 10002,不签发令牌
        RiskRuleType rt = null;
        if (policyId == null) {
            if (accountId == null) {
                throw new McpToolParamInvalidException("accountId required for new risk policy");
            }
            if (ruleType == null) {
                throw new McpToolParamInvalidException("ruleType required for new risk policy");
            }
            rt = parseParam(ruleType, s -> RiskRuleType.valueOf(s.toUpperCase()), "ruleType");
        }
        // 规范化指纹:params 用 TreeMap 排序保证"复述相同参数"指纹稳定
        String canonical =
                canonical(policyId, accountId, ruleType, name, params == null ? null : new TreeMap<>(params), enabled);
        if (confirmToken == null || confirmToken.isBlank()) {
            var issue = confirmTokenService.issue(userId, TOOL_SET_RULES, canonical);
            return new ConfirmRequiredView(
                    TOOL_SET_RULES,
                    issue.token(),
                    issue.expiresInSec(),
                    new RiskRulesPreview(policyId, accountId, ruleType, name, params, enabled));
        }
        confirmTokenService.consume(userId, TOOL_SET_RULES, canonical, confirmToken);
        RiskPolicy result;
        if (policyId != null) {
            result = policyService.update(policyId, userId, name, params);
            if (enabled != null) {
                result = policyService.toggle(policyId, userId, enabled);
            }
        } else {
            result = policyService.create(accountId, userId, rt, name, params);
            if (enabled != null && !enabled) {
                result = policyService.toggle(result.getId(), userId, false);
            }
        }
        return RiskPolicyView.from(result);
    }

    @McpTool(
            name = "emergency_stop",
            description = "紧急停止当前用户所有运行中策略(高危, **两阶段确认**:不带 confirmToken 先返将停策略清单+令牌, "
                    + "复述+令牌再执行). 前置同步审计(EMERGENCY_STOP, batchUuid), 审计失败则策略未停. "
                    + "返 {batchUuid, stoppedCount, strategyIds, failedStrategyIds}.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true))
    public Object emergencyStop(
            @McpToolParam(description = "两阶段确认令牌(第二阶段传第一阶段返回值)", required = false) String confirmToken) {
        scopeGuard.require(McpTokenScope.RISK);
        long userId = SecurityUtils.currentUserId();
        // 无业务参数 → 指纹为常量(预览展示当前 RUNNING 快照,执行时重查最新状态)
        String canonical = "";
        if (confirmToken == null || confirmToken.isBlank()) {
            var issue = confirmTokenService.issue(userId, TOOL_EMERGENCY_STOP, canonical);
            List<EmergencyStopPreview.StrategyRef> running = strategyCrudService.listByUser(userId).stream()
                    .filter(s -> s.getStatus() == StrategyStatus.RUNNING)
                    .map(s -> new EmergencyStopPreview.StrategyRef(s.getId(), s.getName()))
                    .toList();
            return new ConfirmRequiredView(
                    TOOL_EMERGENCY_STOP, issue.token(), issue.expiresInSec(), new EmergencyStopPreview(running));
        }
        confirmTokenService.consume(userId, TOOL_EMERGENCY_STOP, canonical, confirmToken);
        String batchUuid = UUID.randomUUID().toString();
        // 前置显式审计：同步独立短事务，先落审计后停策略
        AuditEntry entry = new AuditEntry(
                String.valueOf(userId),
                CriticalAuditActions.EMERGENCY_STOP,
                "STRATEGY",
                batchUuid,
                null,
                AuditEntry.STATUS_SUCCESS,
                null,
                Map.of(),
                Instant.now());
        try {
            auditRepository.save(entry);
        } catch (RuntimeException e) {
            throw new CriticalAuditException(CriticalAuditActions.EMERGENCY_STOP, e);
        }
        // 审计成功后停所有 RUNNING 策略
        List<StrategyDefinition> running = strategyCrudService.listByUser(userId).stream()
                .filter(s -> s.getStatus() == StrategyStatus.RUNNING)
                .toList();
        List<Long> stoppedIds = new ArrayList<>();
        List<Long> failedIds = new ArrayList<>();
        for (StrategyDefinition s : running) {
            try {
                lifecycleService.stop(s.getId(), userId);
                stoppedIds.add(s.getId());
            } catch (RuntimeException e) {
                // 部分失败：不中断，但暴露 failedStrategyIds 给 operator（kill switch 运维盲区：
                // 未停止策略 ID 须可见，与前置 EMERGENCY_STOP 审计 fail-closed 严肃性一致）
                failedIds.add(s.getId());
                log.warn(
                        "emergency_stop partial failure: batchUuid={} strategyId={} err={}",
                        batchUuid,
                        s.getId(),
                        e.toString());
            }
        }
        return new EmergencyStopView(batchUuid, stoppedIds.size(), stoppedIds, failedIds);
    }

    private static <T> T parseParam(String raw, Function<String, T> parser, String desc) {
        try {
            return parser.apply(raw);
        } catch (RuntimeException e) {
            throw new McpToolParamInvalidException("invalid " + desc + ": " + raw);
        }
    }

    /** 规范化参数串(指纹用):解析后值拼接,保证"复述相同参数"产生相同指纹。 */
    private static String canonical(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            sb.append(p == null ? "∅" : String.valueOf(p)).append('|');
        }
        return sb.toString();
    }
}
