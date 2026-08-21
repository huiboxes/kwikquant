package com.kwikquant.risk.application;

import com.kwikquant.risk.domain.RiskRuleType;
import java.util.Map;

/**
 * 批量应用风控策略的单条指令(自然语言风控"确认后落库"的编排入参)。
 *
 * @param policyId 已有策略 ID —— 非空表示覆盖更新该策略(ruleType 不可改,忽略入参 ruleType);空表示新建
 * @param ruleType 规则类型(新建必填;更新时忽略)
 * @param name     策略名称
 * @param params   规则参数(校验走 {@link RiskPolicyParamValidator})
 */
public record RiskPolicyApplyItem(Long policyId, RiskRuleType ruleType, String name, Map<String, String> params) {}
