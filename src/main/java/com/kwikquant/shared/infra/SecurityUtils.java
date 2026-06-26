package com.kwikquant.shared.infra;

import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static long currentUserId() {
        Objects.requireNonNull(
                SecurityContextHolder.getContext().getAuthentication(), "No authentication in security context");
        return Long.parseLong(
                SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
