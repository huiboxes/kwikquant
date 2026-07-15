package com.kwikquant.shared.infra;

import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    /** Spring Security 匿名用户的默认 principal 值（未认证请求）。 */
    public static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

    private SecurityUtils() {}

    public static long currentUserId() {
        return Long.parseLong(
                SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
