package com.example.ordermanagement.inventory;

import com.example.ordermanagement.common.BadRequestException;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public record StockMovementQuery(int page, int size, String sort) {
    public StockMovementQuery {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BadRequestException("page must be nonnegative, size must be 1 to 100, and offset must fit an int");
        }
        sort = sort == null ? "createdAt,desc" : sort.strip();
        parseSort(sort);
    }

    public PageRequest pageable() {
        return PageRequest.of(page, size, parseSort(sort));
    }

    private static Sort parseSort(String value) {
        String[] parts = value.split(",", -1);
        String direction = parts.length == 1 ? "desc" : parts[1].strip().toLowerCase(Locale.ROOT);
        if (parts.length > 2 || !parts[0].strip().equals("createdAt")
                || !(direction.equals("asc") || direction.equals("desc"))) {
            throw new BadRequestException("sort must be createdAt,asc or createdAt,desc");
        }
        return Sort.by(Sort.Direction.fromString(direction), "createdAt", "id");
    }
}
