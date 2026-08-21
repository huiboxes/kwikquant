package com.kwikquant.shared.infra;

import com.kwikquant.shared.types.McpTokenIssueResult;
import com.kwikquant.shared.types.McpTokenPrincipal;
import com.kwikquant.shared.types.McpTokenScope;
import com.kwikquant.shared.types.McpTokenView;
import java.util.List;
import java.util.Set;

/**
 * MCP PAT 签发/验证/吊销服务（跨模块中立，归 shared/infra）。
 *
 * <p>类比 {@code WorkerTokenService}（shared/infra，跨模块中立），但 PAT 是 DB 持久化 + HMAC 哈希
 * （Worker token 是内存 registry 短命）。account 的 issue controller 与 mcp 的 filter 都依赖，放任一端
 * 都会跨模块违规，故归 shared/infra。
 */
public interface McpTokenService {

    /**
     * 生成 + 哈希存储，返回明文 token（仅此一次）。同用户同名抛 {@link DuplicateMcpTokenException}。
     *
     * @param scopes 权限域;null/空 → 默认最小权限 {@link McpTokenScope#DEFAULT}(仅 READ)
     * @param expiresInDays 有效期天数;null → 默认 90 天,上限 365(强制 TTL,杜绝永久全权凭证)
     */
    McpTokenIssueResult issue(long userId, String name, Set<McpTokenScope> scopes, Integer expiresInDays);

    /** 设 revoked_at。tokenId 不属该用户或不存在/已吊销 → 抛 {@link ResourceNotFoundException}。 */
    void revoke(long tokenId, long userId);

    /** 列出用户的所有 PAT（脱敏，不返 token 明文/tokenHash/salt）。 */
    List<McpTokenView> listByUser(long userId);

    /**
     * 验证 rawToken。返 principal(userId + scopes)，无效（不存在/已吊销/已过期）返 null。
     * verify 的 last_used_at 更新走独立事务 + try-catch swallow，失败不阻断鉴权放行（Fail-open on touch, Fail-closed on auth）。
     */
    McpTokenPrincipal verify(String rawToken);
}
