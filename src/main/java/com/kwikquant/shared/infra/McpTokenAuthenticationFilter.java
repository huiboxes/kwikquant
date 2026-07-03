package com.kwikquant.shared.infra;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MCP PAT 鉴权 filter（§3.2）。过滤 {@code /mcp/**}：提取 {@code Authorization: Bearer <PAT>}，
 * 调 {@link McpTokenService#verify} 验证，有效→注入 {@code SecurityContext}（principal=userId，与
 * {@code JwtAuthenticationFilter} 一致，下游 {@code SecurityUtils.currentUserId()} 取用），无效→401+10001。
 *
 * <p>归 {@code shared/infra}（非 {@code mcp/infrastructure}）：filter 注册点在
 * {@code account/infrastructure/SecurityConfig}，若 filter 在 mcp/infra 则 account→mcp→account 循环，
 * {@code ModularityTests.verify()} 启动即挂。与 {@code WorkerTokenFilter} 同模式（Wave 8 review M1 修复）。
 *
 * <p>401 直接写 response（{@code @RestControllerAdvice} 捕不到 filter 异常，与 WorkerTokenFilter 一致，
 * 不抛 {@code McpTokenInvalidException}，直接调 {@link JsonErrorWriter#write} 写 10001 JSON）。
 *
 * <p>try/finally 清理 {@code SecurityContextHolder}：复刻 WorkerTokenFilter Round-7 修复，防 Tomcat 线程池
 * ThreadLocal 跨用户身份漂移。非 {@code /mcp} 路径直通 {@code chain.doFilter}，不抢 {@code /api/v1} 请求
 * （让 JwtAuthenticationFilter 接管）。
 */
public class McpTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final McpTokenService tokenService;

    public McpTokenAuthenticationFilter(McpTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!isMcpEndpoint(path)) {
            chain.doFilter(req, resp);
            return;
        }
        String rawToken = extractBearer(req.getHeader("Authorization"));
        // verify 返 null（token 缺失/不存在/已吊销/已过期/DB 故障）一律 Fail-closed → 401
        Long userId = rawToken == null ? null : tokenService.verify(rawToken);
        if (userId == null) {
            JsonErrorWriter.write(
                    resp, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.MCP_TOKEN_INVALID, "mcp token invalid");
            return;
        }
        // principal=userId(String)，与 JwtAuthenticationFilter 一致；JwtFilter 见 auth 非 null 跳过，
        // 不覆盖身份（Round-8 深度防御）。
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, List.of()));
        try {
            chain.doFilter(req, resp);
        } finally {
            // 防 Tomcat 线程池 ThreadLocal 跨用户身份漂移（复刻 WorkerTokenFilter Round-7 修复）
            SecurityContextHolder.clearContext();
        }
    }

    /** MCP 端点：POST /mcp 及 /mcp/** 子路径（Streamable-HTTP 单端点 + 可能的子路径）。 */
    private boolean isMcpEndpoint(String path) {
        if (path == null) return false;
        return path.equals("/mcp") || path.startsWith("/mcp/");
    }

    private String extractBearer(String authHeader) {
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) return null;
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
