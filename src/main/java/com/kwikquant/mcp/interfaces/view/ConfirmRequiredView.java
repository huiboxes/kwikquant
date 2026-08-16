package com.kwikquant.mcp.interfaces.view;

/**
 * 高危写操作两阶段确认的第一阶段响应(**非错误,不执行任何副作用**)。
 *
 * <p>Agent 流程:第一次调用 → 收到本视图(操作预览 + 一次性 confirmToken);向人类展示 preview 获认可后,
 * **复述完全相同的参数**并附 confirmToken 再次调用 → 服务端校验指纹通过才执行。
 *
 * @param tool 工具名(与第二阶段调用一致)
 * @param confirmToken 一次性确认令牌(默认 120s 过期,参数指纹绑定,消费即失效)
 * @param expiresInSec 令牌有效期
 * @param preview 操作预览(各工具专属 record:订单要素/平仓标的/将停策略清单等)
 */
public record ConfirmRequiredView(String tool, String confirmToken, long expiresInSec, Object preview) {}
