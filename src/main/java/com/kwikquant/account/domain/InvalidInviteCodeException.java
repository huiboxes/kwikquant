package com.kwikquant.account.domain;

/**
 * 邀请码无效(不存在 / 已禁用 / 已过期 / 已用尽)。
 *
 * <p>{@code AuthErrorAdvice} 映射 HTTP 400 + {@code ErrorCode.INVITE_CODE_INVALID}(3002)。
 */
public class InvalidInviteCodeException extends RuntimeException {

    public InvalidInviteCodeException() {
        super("invalid invite code");
    }
}
