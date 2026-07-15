package com.kwikquant.shared.infra;

/**
 * 用户可见 label/name 字段的白名单校验正则，供 account 模块下多个 Controller
 * （{@code ExchangeAccountController}/{@code LlmApiKeyController}/{@code McpTokenController}）复用。
 *
 * <p>只允许字母/数字/空格/下划线/中划线，拒绝 "sk-"/"@"/"." 等常出现在 secret 或 email 中的字符——
 * 这些 label 会作为 {@code @Auditable} 的 targetId 写入审计日志，白名单防止用户误把敏感前缀固化进日志。
 */
public final class LabelPatterns {

    private static final String CHARSET = "A-Za-z0-9 _-";

    /** 1-100 字符，字母/数字/空格/_/-。 */
    public static final String LABEL_100 = "^[" + CHARSET + "]{1,100}$";

    /** 1-64 字符，字母/数字/空格/_/-（与 V18 迁移的 VARCHAR(64) 列宽对齐）。 */
    public static final String LABEL_64 = "^[" + CHARSET + "]{1,64}$";

    private LabelPatterns() {}
}
