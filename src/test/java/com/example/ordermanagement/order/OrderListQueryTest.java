package com.example.ordermanagement.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ordermanagement.common.BadRequestException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.Sort;

class OrderListQueryTest {
    @Test
    void usesStableNewestFirstOrderAndExistingPaginationDefaults() {
        var search = new OrderListQuery(null, null, null, 0, 20);
        assertThat(search.pageable().getPageSize()).isEqualTo(20);
        assertThat(search.pageable().getOffset()).isZero();
        assertThat(search.pageable().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,-1", "0,101", "2147483647,100", "21474837,100"})
    void rejectsInvalidPageSizeOrJpaOffset(int page, int size) {
        assertThatThrownBy(() -> new OrderListQuery(null, null, null, page, size))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void acceptsMaximumJpaOffsetWithoutIntegerOverflow() {
        assertThat(new OrderListQuery(null, null, null, Integer.MAX_VALUE, 1).pageable().getOffset())
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(new OrderListQuery(null, null, null, 0, 100).pageable().getPageSize()).isEqualTo(100);
    }

    @Test
    void rejectsReversedTimeRange() {
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        assertThatThrownBy(() -> new OrderListQuery(null, now.plusSeconds(1), now, 0, 20))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("from");
    }

    @Test
    void acceptsEqualAndOneSidedTimeBounds() {
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        assertThat(new OrderListQuery(OrderStatus.CREATED, now, now, 0, 20).from()).isEqualTo(now);
        assertThat(new OrderListQuery(null, now, null, 0, 20).to()).isNull();
        assertThat(new OrderListQuery(null, null, now, 0, 20).from()).isNull();
    }
}
