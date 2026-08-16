package com.kwikquant.shared.infra;

/**
 * 高危写操作两阶段确认令牌无效(过期/已消费/参数指纹不符/跨用户)。
 * 映射 {@link ErrorCode#MCP_CONFIRM_TOKEN_INVALID} (10006),HTTP 400。
 *
 * <p>协议:第一阶段调用(不带 confirmToken)不执行,返回 preview + 新令牌;第二阶段携令牌复述
 * **完全相同**的参数执行。指纹绑定参数,防"预览 A 确认 B"替换攻击;一次性消费防重放。
 * 注意:缺令牌不是错误(走 preview 分支),只有令牌存在但校验不过才抛此异常。
 */
public class McpConfirmTokenInvalidException extends RuntimeException {
    public McpConfirmTokenInvalidException(String message) {
        super(message);
    }
}
