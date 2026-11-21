package com.example.ordermanagement.product;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

final class ProductSpecifications {
    private ProductSpecifications() { }

    static Specification<Product> matching(ProductSearchQuery search, Boolean active) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (active != null) {
                predicates.add(active ? builder.isTrue(root.get("active")) : builder.isFalse(root.get("active")));
            }
            if (search.keyword() != null && !search.keyword().isBlank()) {
                // Treat LIKE metacharacters as literal input; parameters remain bound by JPA.
                String keyword = search.keyword().toLowerCase(Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_");
                predicates.add(builder.like(builder.lower(root.get("name")), "%" + keyword + "%", '!'));
            }
            if (search.minPrice() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("price"), search.minPrice()));
            }
            if (search.maxPrice() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("price"), search.maxPrice()));
            }
            if (search.inStock() != null) {
                predicates.add(search.inStock()
                        ? builder.greaterThan(root.get("stockQuantity"), 0)
                        : builder.equal(root.get("stockQuantity"), 0));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
