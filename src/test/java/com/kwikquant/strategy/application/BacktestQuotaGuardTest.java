package com.kwikquant.strategy.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.kwikquant.strategy.domain.BacktestQuotaExceededException;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** BacktestQuotaGuard 单测:lock → count → insert 顺序与配额拒绝语义。并发串行化的真实验证见集成测试。 */
class BacktestQuotaGuardTest {

    private BacktestTaskMapper taskMapper;
    private BacktestQuotaGuard guard;

    @BeforeEach
    void setUp() {
        taskMapper = mock(BacktestTaskMapper.class);
        guard = new BacktestQuotaGuard(taskMapper, 2);
    }

    private static BacktestTask task(long userId) {
        BacktestTask t = BacktestTask.create(
                1L, userId, 5L, "BTC/USDT", "BINANCE", "SPOT", "1h", Instant.now(), Instant.now(), "{}");
        return t;
    }

    @Test
    void insertWithinQuota_underQuota_lockThenCountThenInsert() {
        when(taskMapper.countActiveByUser(42L)).thenReturn(1);

        BacktestTask t = task(42L);
        BacktestTask inserted = guard.insertWithinQuota(t);

        assertSame(t, inserted);
        InOrder inOrder = inOrder(taskMapper);
        inOrder.verify(taskMapper).lockBacktestQuota(42L);
        inOrder.verify(taskMapper).countActiveByUser(42L);
        inOrder.verify(taskMapper).insert(t);
    }

    @Test
    void insertWithinQuota_quotaFull_throwsWithoutInsert() {
        when(taskMapper.countActiveByUser(42L)).thenReturn(2);

        BacktestQuotaExceededException e =
                assertThrows(BacktestQuotaExceededException.class, () -> guard.insertWithinQuota(task(42L)));
        assertEquals(2, e.active());
        assertEquals(2, e.max());
        verify(taskMapper).lockBacktestQuota(42L); // 锁已取(事务回滚自动释放)
        verify(taskMapper).countActiveByUser(42L);
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void insertWithinQuota_lockKeyedPerUser_otherUsersUnaffected() {
        when(taskMapper.countActiveByUser(anyLong())).thenReturn(0);
        guard.insertWithinQuota(task(7L));
        guard.insertWithinQuota(task(8L));
        verify(taskMapper).lockBacktestQuota(7L);
        verify(taskMapper).lockBacktestQuota(8L);
    }
}
