package com.kwikquant.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.shared.types.StrategyStatus;
import com.kwikquant.strategy.domain.IllegalStrategyStateTransitionException;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyNotFoundException;
import com.kwikquant.strategy.infrastructure.StrategyMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StrategyCrudServiceTest {

    private StrategyMapper mapper;
    private StrategyCrudService service;

    @BeforeEach
    void setUp() {
        mapper = mock(StrategyMapper.class);
        service = new StrategyCrudService(mapper);
    }

    @Test
    void create_setsDraftStatusAndInserts() {
        StrategyDefinition created = service.create(1L, "MA Cross", "desc", "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        assertEquals(StrategyStatus.DRAFT, created.getStatus());
        assertEquals("MA Cross", created.getName());
        assertFalse(created.isDeleted());
        verify(mapper).insert(any(StrategyDefinition.class));
    }

    @Test
    void getOwned_returnsWhenOwner() {
        StrategyDefinition s = draftStrategy(1L, 42L);
        when(mapper.findById(1L)).thenReturn(s);

        StrategyDefinition result = service.getOwned(1L, 42L);
        assertEquals(1L, result.getId());
    }

    @Test
    void getOwned_throws404WhenNotFound() {
        when(mapper.findById(999L)).thenReturn(null);
        assertThrows(StrategyNotFoundException.class, () -> service.getOwned(999L, 42L));
    }

    @Test
    void getOwned_throws403WhenNotOwner() {
        StrategyDefinition s = draftStrategy(1L, 99L);
        when(mapper.findById(1L)).thenReturn(s);
        assertThrows(OwnershipViolationException.class, () -> service.getOwned(1L, 42L));
    }

    @Test
    void listByUser() {
        when(mapper.findByUserId(1L)).thenReturn(List.of(draftStrategy(1L, 1L)));
        assertEquals(1, service.listByUser(1L).size());
    }

    @Test
    void update_draftSucceeds() {
        StrategyDefinition s = draftStrategy(1L, 42L);
        when(mapper.findById(1L)).thenReturn(s);
        when(mapper.update(any(StrategyDefinition.class))).thenReturn(1);

        service.update(1L, 42L, "EMA", "d", "ETH/USDT", "BINANCE", "SPOT", "1h", "{}");

        verify(mapper).update(any(StrategyDefinition.class));
        assertEquals("EMA", s.getName());
        assertEquals("ETH/USDT", s.getSymbol());
    }

    @Test
    void update_deepDefenseFails_throwsConflict() {
        // Round 2 修：mapper.update WHERE 含 user_id + deleted=FALSE，返回 0 = 并发/owner 变更
        StrategyDefinition s = draftStrategy(1L, 42L);
        when(mapper.findById(1L)).thenReturn(s);
        when(mapper.update(any(StrategyDefinition.class))).thenReturn(0); // 深防御触发

        assertThrows(
                com.kwikquant.shared.infra.ResourceStateConflictException.class,
                () -> service.update(1L, 42L, "EMA", "d", "ETH/USDT", "BINANCE", "SPOT", "1h", "{}"));
    }

    @Test
    void update_runningThrowsIllegalTransition() {
        StrategyDefinition s = draftStrategy(1L, 42L);
        s.setStatus(StrategyStatus.RUNNING);
        when(mapper.findById(1L)).thenReturn(s);

        assertThrows(
                IllegalStrategyStateTransitionException.class,
                () -> service.update(1L, 42L, "EMA", "d", "ETH/USDT", "BINANCE", "SPOT", "1h", "{}"));
        verify(mapper, never()).update(any());
    }

    @Test
    void update_stoppedSucceeds() {
        StrategyDefinition s = draftStrategy(1L, 42L);
        s.setStatus(StrategyStatus.STOPPED);
        when(mapper.findById(1L)).thenReturn(s);
        when(mapper.update(any(StrategyDefinition.class))).thenReturn(1);

        service.update(1L, 42L, "new", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        verify(mapper).update(any(StrategyDefinition.class));
    }

    @Test
    void delete_draftSoftDeletes() {
        StrategyDefinition s = draftStrategy(1L, 42L);
        when(mapper.findById(1L)).thenReturn(s);
        when(mapper.softDelete(1L, 42L)).thenReturn(1);

        service.delete(1L, 42L);
        verify(mapper).softDelete(1L, 42L);
    }

    @Test
    void delete_deepDefenseFails_throwsConflict() {
        // Round 2 修：softDelete 返回 0 = 并发已删或 owner 变更
        StrategyDefinition s = draftStrategy(1L, 42L);
        when(mapper.findById(1L)).thenReturn(s);
        when(mapper.softDelete(1L, 42L)).thenReturn(0);

        assertThrows(com.kwikquant.shared.infra.ResourceStateConflictException.class, () -> service.delete(1L, 42L));
    }

    @Test
    void delete_runningThrows() {
        StrategyDefinition s = draftStrategy(1L, 42L);
        s.setStatus(StrategyStatus.RUNNING);
        when(mapper.findById(1L)).thenReturn(s);

        assertThrows(IllegalStrategyStateTransitionException.class, () -> service.delete(1L, 42L));
        verify(mapper, never()).softDelete(anyLong(), anyLong());
    }

    @Test
    void findById_internal_throwsWhenNotFound() {
        when(mapper.findById(1L)).thenReturn(null);
        assertThrows(StrategyNotFoundException.class, () -> service.findById(1L));
    }

    @Test
    void findRunningStrategies_forReconcile() {
        StrategyDefinition s = draftStrategy(1L, 42L);
        s.setStatus(StrategyStatus.RUNNING);
        when(mapper.findByStatus("RUNNING")).thenReturn(List.of(s));

        List<StrategyDefinition> running = service.findRunningStrategies();
        assertEquals(1, running.size());
        verify(mapper).findByStatus("RUNNING");
    }

    private StrategyDefinition draftStrategy(long id, long userId) {
        StrategyDefinition s = StrategyDefinition.create(userId, "n", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(id);
        return s;
    }
}
