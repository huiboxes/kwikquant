package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.shared.types.McpToken;
import com.kwikquant.shared.types.McpTokenIssueResult;
import com.kwikquant.shared.types.McpTokenView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link McpTokenServiceImpl} 单元测试（Mockito，mock mapper + hasher，{@code new} 直接构造）。
 * 覆盖 issue/revoke/listByUser/verify（命中/无效/已吊销/过期/last_used_at 失败不影响放行）。
 */
class McpTokenServiceTest {

    private McpTokenMapper mapper;
    private McpTokenHasher hasher;
    private McpTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(McpTokenMapper.class);
        hasher = mock(McpTokenHasher.class);
        service = new McpTokenServiceImpl(mapper, hasher);
        // self=null → verify 回退到 this 直调，@Transactional 不生效（mock mapper 直抛可测 swallow）
    }

    @Test
    void issue_generatesHashesAndReturnsRawToken() {
        when(hasher.generateToken()).thenReturn("kq_pat_rawtoken");
        when(hasher.generateSalt()).thenReturn("aabbccdd");
        when(hasher.hash("kq_pat_rawtoken", "")).thenReturn("hashedvalue");

        McpTokenIssueResult result = service.issue(42L, "claude-desktop");

        // mock mapper.insert 不触发 useGeneratedKeys，id 为 null（实现层 @Options 仅在真实 DB 生效）
        assertThat(result.token()).isEqualTo("kq_pat_rawtoken");
        assertThat(result.name()).isEqualTo("claude-desktop");
        assertThat(result.createdAt()).isNotNull();

        McpToken captured = new McpToken();
        // 捕获 insert 入参
        verify(mapper)
                .insert(argThat(t -> t != null
                        && t.getUserId() == 42L
                        && t.getName().equals("claude-desktop")
                        && t.getTokenHash().equals("hashedvalue")
                        && t.getSalt().equals("aabbccdd")));
    }

    @Test
    void issue_duplicateNameThrowsDuplicateMcpTokenException() {
        when(hasher.generateToken()).thenReturn("kq_pat_dup");
        when(hasher.generateSalt()).thenReturn("s");
        when(hasher.hash(anyString(), anyString())).thenReturn("h");
        doThrow(new DataIntegrityViolationException("uk_mcp_user_name"))
                .when(mapper)
                .insert(any(McpToken.class));

        assertThatThrownBy(() -> service.issue(1L, "dup")).isInstanceOf(DuplicateMcpTokenException.class);
    }

    @Test
    void revoke_setsRevokedAt() {
        when(mapper.updateRevokedAt(10L, 1L)).thenReturn(1);
        service.revoke(10L, 1L);
        verify(mapper).updateRevokedAt(10L, 1L);
    }

    @Test
    void revoke_notFoundOrRevokedOrForbidden_throwsResourceNotFound() {
        when(mapper.updateRevokedAt(anyLong(), anyLong())).thenReturn(0);
        assertThatThrownBy(() -> service.revoke(99L, 1L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listByUser_returnsViewsExposedTokenHash() {
        McpToken t = new McpToken();
        t.setId(7L);
        t.setUserId(1L);
        t.setName("my-pat");
        t.setTokenHash("secret-hash");
        t.setSalt("secret-salt");
        t.setCreatedAt(Instant.now());
        when(mapper.findByUserId(1L)).thenReturn(List.of(t));

        List<McpTokenView> views = service.listByUser(1L);

        assertThat(views).hasSize(1);
        McpTokenView v = views.get(0);
        assertThat(v.id()).isEqualTo(7L);
        assertThat(v.name()).isEqualTo("my-pat");
        // View 不含 tokenHash/salt（record 无该字段，编译期保证）
    }

    @Test
    void verify_nullTokenReturnsNull() {
        assertThat(service.verify(null)).isNull();
        assertThat(service.verify("")).isNull();
        assertThat(service.verify("   ")).isNull();
    }

    @Test
    void verify_tokenNotFoundReturnsNull() {
        when(hasher.hash("kq_pat_x", "")).thenReturn("h");
        when(mapper.findByTokenHash("h")).thenReturn(null);
        assertThat(service.verify("kq_pat_x")).isNull();
    }

    @Test
    void verify_revokedTokenReturnsNull() {
        McpToken t = tokenWith(1L, null, null);
        t.setRevokedAt(Instant.now());
        when(hasher.hash("kq_pat_x", "")).thenReturn("h");
        when(mapper.findByTokenHash("h")).thenReturn(t);
        assertThat(service.verify("kq_pat_x")).isNull();
        verify(mapper, never()).updateLastUsedAt(anyLong());
    }

    @Test
    void verify_expiredTokenReturnsNull() {
        McpToken t = tokenWith(1L, null, Instant.now().minus(1, ChronoUnit.MINUTES));
        when(hasher.hash("kq_pat_x", "")).thenReturn("h");
        when(mapper.findByTokenHash("h")).thenReturn(t);
        assertThat(service.verify("kq_pat_x")).isNull();
        verify(mapper, never()).updateLastUsedAt(anyLong());
    }

    @Test
    void verify_validTokenReturnsUserIdAndTouchesLastUsedAt() {
        McpToken t = tokenWith(42L, null, null);
        when(hasher.hash("kq_pat_x", "")).thenReturn("h");
        when(mapper.findByTokenHash("h")).thenReturn(t);
        when(mapper.updateLastUsedAt(t.getId())).thenReturn(1);

        assertThat(service.verify("kq_pat_x")).isEqualTo(42L);
        verify(mapper).updateLastUsedAt(t.getId());
    }

    @Test
    void verify_lastUsedAtUpdateFailureDoesNotBlockAuth() {
        McpToken t = tokenWith(42L, null, null);
        t.setId(99L);
        when(hasher.hash("kq_pat_x", "")).thenReturn("h");
        when(mapper.findByTokenHash("h")).thenReturn(t);
        // mapper.updateLastUsedAt 抛 DataAccessException → swallow → 仍返 userId
        when(mapper.updateLastUsedAt(99L)).thenThrow(new DataAccessException("db down") {});

        assertThat(service.verify("kq_pat_x")).isEqualTo(42L);
    }

    private static McpToken tokenWith(long userId, Instant revokedAt, Instant expiresAt) {
        McpToken t = new McpToken();
        t.setId(System.nanoTime() % 1000);
        t.setUserId(userId);
        t.setName("test");
        t.setTokenHash("h");
        t.setSalt("s");
        t.setRevokedAt(revokedAt);
        t.setExpiresAt(expiresAt);
        t.setCreatedAt(Instant.now());
        return t;
    }
}
