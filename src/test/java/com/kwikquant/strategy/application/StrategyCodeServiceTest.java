package com.kwikquant.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.strategy.domain.IllegalStrategyCodeStateTransitionException;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyCodeNotFoundException;
import com.kwikquant.strategy.domain.StrategyCodeStatus;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.infrastructure.StrategyCodeMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StrategyCodeServiceTest {

    private StrategyCodeMapper codeMapper;
    private StrategyCrudService crudService;
    private StrategyCodeService service;

    @BeforeEach
    void setUp() {
        codeMapper = mock(StrategyCodeMapper.class);
        crudService = mock(StrategyCrudService.class);
        service = new StrategyCodeService(codeMapper, crudService);
    }

    @Test
    void createDraft_firstVersionIs1() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findMaxVersionNumber(1L)).thenReturn(0);

        StrategyCode code = service.createDraft(1L, 42L, "def on_bar(): pass", "initial");

        assertEquals(1, code.getVersionNumber());
        assertEquals(StrategyCodeStatus.DRAFT, code.getStatus());
        assertEquals("python", code.getLanguage());
        verify(codeMapper).insert(any(StrategyCode.class));
    }

    @Test
    void createDraft_versionIncrementsFromMax() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findMaxVersionNumber(1L)).thenReturn(3);

        StrategyCode code = service.createDraft(1L, 42L, "code", "v4");
        assertEquals(4, code.getVersionNumber());
    }

    @Test
    void createDraft_sourceCodeOver1MbThrows() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findMaxVersionNumber(1L)).thenReturn(0);
        String huge = "x".repeat(1_000_001);

        assertThrows(IllegalArgumentException.class, () -> service.createDraft(1L, 42L, huge, null));
        verify(codeMapper, never()).insert(any());
    }

    @Test
    void createDraft_utf8ByteCountEnforced_notCharCount() {
        // spec-review S-3：limit 用 UTF-8 字节数，防含中文注释绕过。
        // 中文 "中" 在 UTF-8 中 3 字节；char count = 400_000，字节数 = 1_200_000 > 1MB 上限 → 抛
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findMaxVersionNumber(1L)).thenReturn(0);
        String cn = "中".repeat(400_000);
        assertThrows(IllegalArgumentException.class, () -> service.createDraft(1L, 42L, cn, null));
        verify(codeMapper, never()).insert(any());
    }

    @Test
    void createDraft_utf8UnderLimitAccepted() {
        // 中文 "中" 3 字节；char count = 300_000, 字节数 = 900_000 < 1MB → 通过
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findMaxVersionNumber(1L)).thenReturn(0);
        String cn = "中".repeat(300_000);
        assertDoesNotThrow(() -> service.createDraft(1L, 42L, cn, null));
        verify(codeMapper).insert(any());
    }

    @Test
    void updateDraft_succeeds() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        StrategyCode code = draftCode(5L, 1L);
        when(codeMapper.findById(5L)).thenReturn(code);
        when(codeMapper.updateDraft(5L, 42L, "new code", "edit")).thenReturn(1);

        service.updateDraft(1L, 42L, 5L, "new code", "edit");

        verify(codeMapper).updateDraft(5L, 42L, "new code", "edit");
        assertEquals("new code", code.getSourceCode());
    }

    @Test
    void updateDraft_deepDefenseFails_throwsConflict() {
        // Round 2 修：updateDraft WHERE 含 EXISTS(strategy owner)，返回 0 = 并发/owner 校验失败
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        StrategyCode code = draftCode(5L, 1L);
        when(codeMapper.findById(5L)).thenReturn(code);
        when(codeMapper.updateDraft(anyLong(), anyLong(), anyString(), any())).thenReturn(0);

        assertThrows(ResourceStateConflictException.class, () -> service.updateDraft(1L, 42L, 5L, "new", null));
    }

    @Test
    void updateDraft_publishedThrows() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        StrategyCode code = draftCode(5L, 1L);
        code.setStatus(StrategyCodeStatus.PUBLISHED);
        when(codeMapper.findById(5L)).thenReturn(code);

        assertThrows(
                IllegalStrategyCodeStateTransitionException.class, () -> service.updateDraft(1L, 42L, 5L, "new", null));
        verify(codeMapper, never()).updateDraft(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void updateDraft_codeBelongsToOtherStrategyThrows404() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        StrategyCode code = draftCode(5L, 999L); // belongs to strategy 999
        when(codeMapper.findById(5L)).thenReturn(code);

        assertThrows(StrategyCodeNotFoundException.class, () -> service.updateDraft(1L, 42L, 5L, "new", null));
    }

    @Test
    void publish_archivesOldAndPublishesNew() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        StrategyCode code = draftCode(5L, 1L);
        when(codeMapper.findById(5L)).thenReturn(code);
        when(codeMapper.updateStatus(5L, 42L, "DRAFT", "PUBLISHED")).thenReturn(1);

        StrategyCode published = service.publish(1L, 42L, 5L);

        verify(codeMapper).archiveCurrentPublished(1L, 42L);
        verify(codeMapper).updateStatus(5L, 42L, "DRAFT", "PUBLISHED");
        assertEquals(StrategyCodeStatus.PUBLISHED, published.getStatus());
    }

    @Test
    void publish_casFailureThrowsConflict() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        StrategyCode code = draftCode(5L, 1L);
        when(codeMapper.findById(5L)).thenReturn(code);
        when(codeMapper.updateStatus(5L, 42L, "DRAFT", "PUBLISHED")).thenReturn(0); // CAS conflict

        assertThrows(ResourceStateConflictException.class, () -> service.publish(1L, 42L, 5L));
    }

    @Test
    void publish_publishedCodeThrows() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        StrategyCode code = draftCode(5L, 1L);
        code.setStatus(StrategyCodeStatus.PUBLISHED);
        when(codeMapper.findById(5L)).thenReturn(code);

        assertThrows(IllegalStrategyCodeStateTransitionException.class, () -> service.publish(1L, 42L, 5L));
        verify(codeMapper, never()).archiveCurrentPublished(anyLong(), anyLong());
    }

    @Test
    void deleteCode_draftSucceeds() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        StrategyCode code = draftCode(5L, 1L);
        when(codeMapper.findById(5L)).thenReturn(code);
        when(codeMapper.deleteDraft(5L, 42L)).thenReturn(1);

        service.deleteCode(1L, 42L, 5L);

        verify(codeMapper).deleteDraft(5L, 42L);
    }

    @Test
    void deleteCode_publishedThrows() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        StrategyCode code = draftCode(5L, 1L);
        code.setStatus(StrategyCodeStatus.PUBLISHED);
        when(codeMapper.findById(5L)).thenReturn(code);

        assertThrows(IllegalStrategyCodeStateTransitionException.class, () -> service.deleteCode(1L, 42L, 5L));
        verify(codeMapper, never()).deleteDraft(anyLong(), anyLong());
    }

    @Test
    void deleteCode_archivedThrows() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        StrategyCode code = draftCode(5L, 1L);
        code.setStatus(StrategyCodeStatus.ARCHIVED);
        when(codeMapper.findById(5L)).thenReturn(code);

        assertThrows(IllegalStrategyCodeStateTransitionException.class, () -> service.deleteCode(1L, 42L, 5L));
        verify(codeMapper, never()).deleteDraft(anyLong(), anyLong());
    }

    @Test
    void deleteCode_deepDefenseFails_throwsConflict() {
        // deleteDraft WHERE 含 EXISTS(strategy owner)，返回 0 = 并发发布/归档或 owner 变更
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findById(5L)).thenReturn(draftCode(5L, 1L));
        when(codeMapper.deleteDraft(anyLong(), anyLong())).thenReturn(0);

        assertThrows(ResourceStateConflictException.class, () -> service.deleteCode(1L, 42L, 5L));
    }

    @Test
    void deleteCode_codeNotFound_throws() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findById(99L)).thenReturn(null);

        assertThrows(StrategyCodeNotFoundException.class, () -> service.deleteCode(1L, 42L, 99L));
    }

    @Test
    void deleteCode_codeBelongsToOtherStrategy_throws() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findById(5L)).thenReturn(draftCode(5L, 999L)); // belongs to strategy 999

        assertThrows(StrategyCodeNotFoundException.class, () -> service.deleteCode(1L, 42L, 5L));
    }

    @Test
    void getPublishedCode_returnsPublished() {
        StrategyCode code = draftCode(5L, 1L);
        code.setStatus(StrategyCodeStatus.PUBLISHED);
        when(codeMapper.findPublishedByStrategyId(1L)).thenReturn(code);

        StrategyCode result = service.getPublishedCode(1L);
        assertNotNull(result);
        assertEquals(5L, result.getId());
    }

    @Test
    void getPublishedCode_returnsNullWhenNone() {
        when(codeMapper.findPublishedByStrategyId(1L)).thenReturn(null);
        assertNull(service.getPublishedCode(1L));
    }

    @Test
    void listByStrategy() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findByStrategyId(1L)).thenReturn(List.of(draftCode(5L, 1L)));

        List<StrategyCode> codes = service.listByStrategy(1L, 42L);
        assertEquals(1, codes.size());
    }

    @Test
    void getOwnedCode_returnsCodeWithSourceCode() {
        // 契约改动 A：GET /:codeId 返含 sourceCode 正文（list 端点不含）
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findById(5L)).thenReturn(draftCode(5L, 1L));

        StrategyCode code = service.getOwnedCode(1L, 42L, 5L);
        assertNotNull(code);
        assertEquals(5L, code.getId());
        assertNotNull(code.getSourceCode(), "GET /:codeId 应返含 sourceCode 正文");
    }

    @Test
    void getOwnedCode_codeNotFound_throws() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findById(99L)).thenReturn(null);

        assertThrows(StrategyCodeNotFoundException.class, () -> service.getOwnedCode(1L, 42L, 99L));
    }

    @Test
    void getOwnedCode_codeBelongsToOtherStrategy_throws() {
        when(crudService.getOwned(1L, 42L)).thenReturn(strategy(1L, 42L));
        when(codeMapper.findById(5L)).thenReturn(draftCode(5L, 2L)); // strategyId mismatch

        assertThrows(StrategyCodeNotFoundException.class, () -> service.getOwnedCode(1L, 42L, 5L));
    }

    private StrategyDefinition strategy(long id, long userId) {
        StrategyDefinition s = StrategyDefinition.create(userId, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(id);
        return s;
    }

    private StrategyCode draftCode(long id, long strategyId) {
        StrategyCode c = StrategyCode.create(strategyId, 1, "def on_bar(): pass", "v1");
        c.setId(id);
        return c;
    }
}
