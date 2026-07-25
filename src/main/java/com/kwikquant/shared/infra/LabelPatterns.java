package com.kwikquant.shared.infra;

/**
 * 用户可见 label/name 字段的白名单校验正则，供 account 模块下多个 Controller
 * （{@code ExchangeAccountController}/{@code LlmApiKeyController}/{@code McpTokenController}）复用。
 *
 * <p>允许任何语言字母/数字(含中文)/空格/下划线/中划线,拒绝 "."/"@"/"!"等常出现在 secret 或
 * email 中的特殊字符——这些 label 会作为 {@code @Auditable} 的 targetId 写入审计日志,白名单
 * 防止用户误把敏感前缀固化进日志。(原 ASCII-only 正则拒中文 label 如"主账户",改 \p{L}/\p{N}
 * 放开 Unicode 字母/数字;仍拒 .@!$%^& 等 secret 字符。)
 */
public final class LabelPatterns {

    // \p{L}/\p{N} = 任何语言字母/数字(含中文),拒 .@!$%^& 等特殊字符防 secret 前缀固化进审计日志
    private static final String CHARSET = "\\p{L}\\p{N} _-";

    /** 1-100 字符,任何语言字母/数字(含中文)/空格/_/-。 */
    public static final String LABEL_100 = "^[" + CHARSET + "]{1,100}$";

    /** 1-64 字符,任何语言字母/数字(含中文)/空格/_/-(与 V18 迁移的 VARCHAR(64) 列宽对齐)。 */
    public static final String LABEL_64 = "^[" + CHARSET + "]{1,64}$";

    private LabelPatterns() {}
}
