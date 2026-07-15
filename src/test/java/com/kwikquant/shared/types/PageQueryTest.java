package com.kwikquant.shared.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PageQueryTest {

    @Test
    void of_defaults_whenNullInputs() {
        PageQuery pq = PageQuery.of(null, null, 20, 100);
        assertThat(pq.page()).isEqualTo(1);
        assertThat(pq.pageSize()).isEqualTo(20);
        assertThat(pq.offset()).isZero();
    }

    @Test
    void of_usesProvidedValues() {
        PageQuery pq = PageQuery.of(3, 50, 20, 100);
        assertThat(pq.page()).isEqualTo(3);
        assertThat(pq.pageSize()).isEqualTo(50);
        assertThat(pq.offset()).isEqualTo(100);
    }

    @Test
    void of_clampsPageToMinOne() {
        PageQuery pq = PageQuery.of(0, 10, 20, 100);
        assertThat(pq.page()).isEqualTo(1);

        PageQuery pq2 = PageQuery.of(-5, 10, 20, 100);
        assertThat(pq2.page()).isEqualTo(1);
    }

    @Test
    void of_clampsPageSizeToMax() {
        PageQuery pq = PageQuery.of(1, 500, 20, 100);
        assertThat(pq.pageSize()).isEqualTo(100);
    }

    @Test
    void of_fallsBackToDefaultWhenPageSizeZeroOrNegative() {
        PageQuery pq = PageQuery.of(1, 0, 20, 100);
        assertThat(pq.pageSize()).isEqualTo(20);

        PageQuery pq2 = PageQuery.of(1, -1, 20, 100);
        assertThat(pq2.pageSize()).isEqualTo(20);
    }

    @Test
    void offset_computedCorrectly() {
        assertThat(PageQuery.of(1, 20, 20, 100).offset()).isZero();
        assertThat(PageQuery.of(2, 20, 20, 100).offset()).isEqualTo(20);
        assertThat(PageQuery.of(5, 10, 20, 100).offset()).isEqualTo(40);
    }

    @Test
    void of_primitiveOverload() {
        PageQuery pq = PageQuery.of(2, 30, 20, 200);
        assertThat(pq.page()).isEqualTo(2);
        assertThat(pq.pageSize()).isEqualTo(30);
    }

    @Test
    void constructor_rejectsInvalidPage() {
        assertThatThrownBy(() -> new PageQuery(0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page must be >= 1");
    }

    @Test
    void constructor_rejectsInvalidPageSize() {
        assertThatThrownBy(() -> new PageQuery(1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageSize must be >= 1");
    }

    @Test
    void ofStandard_defaults20_max100() {
        PageQuery pq = PageQuery.ofStandard(null, null);
        assertThat(pq.page()).isEqualTo(1);
        assertThat(pq.pageSize()).isEqualTo(20);

        PageQuery clamped = PageQuery.ofStandard(1, 999);
        assertThat(clamped.pageSize()).isEqualTo(100);
    }

    @Test
    void ofLarge_defaults50_max200() {
        PageQuery pq = PageQuery.ofLarge(null, null);
        assertThat(pq.page()).isEqualTo(1);
        assertThat(pq.pageSize()).isEqualTo(50);

        PageQuery clamped = PageQuery.ofLarge(1, 999);
        assertThat(clamped.pageSize()).isEqualTo(200);
    }
}
