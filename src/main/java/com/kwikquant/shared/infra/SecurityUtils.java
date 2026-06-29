package com.kwikquant.shared.infra;

import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static long currentUserId() {
        return Long.parseLong(
                SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
