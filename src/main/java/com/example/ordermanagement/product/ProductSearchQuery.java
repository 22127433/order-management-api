package com.example.ordermanagement.product;

import com.example.ordermanagement.common.BadRequestException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Validated search input shared by HTTP requests and direct service callers. */
public record ProductSearchQuery(String keyword, BigDecimal minPrice, BigDecimal maxPrice,
                                 Boolean inStock, int page, int size, String sort) {
    private static final Set<String> SORT_FIELDS = Set.of("id", "name", "price", "stockQuantity");

    public ProductSearchQuery {
        keyword = keyword == null ? null : keyword.strip();
        if (keyword != null && keyword.length() > 120) {
            throw new BadRequestException("keyword must not exceed 120 characters");
        }
        if ((minPrice != null && minPrice.signum() < 0) || (maxPrice != null && maxPrice.signum() < 0)) {
            throw new BadRequestException("Price bounds must be nonnegative");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BadRequestException("minPrice must not be greater than maxPrice");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("page must be nonnegative and size must be between 1 and 100");
        }
        // JPA's setFirstResult accepts an int even though Pageable exposes a long offset.
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new BadRequestException("Requested page offset exceeds the supported limit");
        }
        sort = sort == null ? "id,asc" : sort.strip();
        parseSort(sort);
    }

    public PageRequest pageable() {
        return PageRequest.of(page, size, parseSort(sort));
    }

    private static Sort parseSort(String value) {
        String[] parts = value.split(",", -1);
        String field = parts[0].strip();
        String direction = parts.length == 1 ? "asc" : parts[1].strip().toLowerCase(Locale.ROOT);
        if (parts.length > 2 || !SORT_FIELDS.contains(field)
                || !(direction.equals("asc") || direction.equals("desc"))) {
            throw new BadRequestException("sort must be field[,asc|desc]; supported fields: id, name, price, stockQuantity");
        }
        Sort result = Sort.by(Sort.Direction.fromString(direction), field);
        // A unique tie-breaker keeps equal-valued products on stable pages.
        return field.equals("id") ? result : result.and(Sort.by("id"));
    }
}
