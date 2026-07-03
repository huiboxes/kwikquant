package com.kwikquant.shared.infra;

import com.kwikquant.shared.types.McpTokenIssueResult;
import com.kwikquant.shared.types.McpTokenView;
import java.util.List;

/**
 * MCP PAT 签发/验证/吊销服务（跨模块中立，归 shared/infra）。
 *
 * <p>类比 Wave 8 {@code WorkerTokenService}（shared/infra，跨模块中立），但 PAT 是 DB 持久化 + HMAC 哈希
 * （Worker token 是内存 registry 短命）。account 的 issue controller 与 mcp 的 filter 都依赖，放任一端
 * 都会跨模块违规，故归 shared/infra（§3.1 模块定位）。
 */
public interface McpTokenService {

    /** 生成 + 哈希存储，返回明文 token（仅此一次）。同用户同名抛 {@link DuplicateMcpTokenException}。 */
    McpTokenIssueResult issue(long userId, String name);

    /** 设 revoked_at。tokenId 不属该用户或不存在/已吊销 → 抛 {@link ResourceNotFoundException}。 */
    void revoke(long tokenId, long userId);

    /** 列出用户的所有 PAT（脱敏，不返 token 明文/tokenHash/salt）。 */
    List<McpTokenView> listByUser(long userId);

    /**
     * 验证 rawToken。返 userId，无效（不存在/已吊销/已过期）返 null。
     * verify 的 last_used_at 更新走独立事务 + try-catch swallow，失败不阻断鉴权放行（Fail-open on touch, Fail-closed on auth）。
     */
    Long verify(String rawToken);
}
