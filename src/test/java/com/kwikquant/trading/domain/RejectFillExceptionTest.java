package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RejectFillExceptionTest {

    @Test
    void messageOnlyConstructor() {
        RejectFillException e = new RejectFillException("fill qty 1.5 exceeds position qty 1.0");
        assertThat(e.getMessage()).isEqualTo("fill qty 1.5 exceeds position qty 1.0");
        assertThat(e.getCause()).isNull();
    }

    @Test
    void messageAndCauseConstructor() {
        Throwable root = new IllegalStateException("position lookup failed mid-fill");
        RejectFillException e = new RejectFillException("cannot verify remaining qty", root);
        assertThat(e.getMessage()).isEqualTo("cannot verify remaining qty");
        assertThat(e.getCause()).isSameAs(root);
    }

    @Test
    void isSubclassOfMatchingException() {
        RejectFillException e = new RejectFillException("any");
        assertThat(e).isInstanceOf(MatchingException.class);
        assertThat(e).isInstanceOf(RuntimeException.class);
    }

    @Test
    void canCatchAsMatchingException() {
        MatchingException caught = catchThrowableOfType(MatchingException.class, () -> {
            throw new RejectFillException("over-fill");
        });
        assertThat(caught.getMessage()).isEqualTo("over-fill");
    }
}
