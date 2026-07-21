package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class InsufficientMarginExceptionTest {

    @Test
    void messageOnlyConstructor() {
        InsufficientMarginException e = new InsufficientMarginException("initialMargin 5000 exceeds free 3000 USDT");
        assertThat(e.getMessage()).isEqualTo("initialMargin 5000 exceeds free 3000 USDT");
        assertThat(e.getCause()).isNull();
    }

    @Test
    void messageAndCauseConstructor() {
        Throwable root = new IllegalStateException("balance lookup failed");
        InsufficientMarginException e = new InsufficientMarginException("cannot verify margin", root);
        assertThat(e.getMessage()).isEqualTo("cannot verify margin");
        assertThat(e.getCause()).isSameAs(root);
    }

    @Test
    void isSubclassOfInsufficientBalanceException() {
        // m9 拍板:复用 account.domain.InsufficientBalanceException 的 ErrorCode + submit catch 链
        // (非同包 trading.domain 那个——后者冒泡 handler,语义不同)
        InsufficientMarginException e = new InsufficientMarginException("any");
        assertThat(e).isInstanceOf(com.kwikquant.account.domain.InsufficientBalanceException.class);
        assertThat(e).isInstanceOf(RuntimeException.class);
    }

    @Test
    void canCatchAsParentType() {
        // 验证 catch(account.domain.InsufficientBalanceException) 能捕获子类 → submit catch 转 REJECTED
        com.kwikquant.account.domain.InsufficientBalanceException caught =
                catchThrowableOfType(com.kwikquant.account.domain.InsufficientBalanceException.class, () -> {
                    throw new InsufficientMarginException("perp margin");
                });
        assertThat(caught.getMessage()).isEqualTo("perp margin");
    }
}
