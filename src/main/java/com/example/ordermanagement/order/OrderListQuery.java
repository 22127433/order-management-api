package com.example.ordermanagement.order;

import com.example.ordermanagement.common.BadRequestException;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Validated filters shared by the HTTP endpoint and direct service callers. No client owner field. */
public record OrderListQuery(OrderStatus status, Instant from, Instant to, int page, int size) {
    public OrderListQuery {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("from must not be after to");
        }
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BadRequestException("page must be nonnegative, size must be 1 to 100, and offset must fit an int");
        }
    }

    public PageRequest pageable() {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }
}
