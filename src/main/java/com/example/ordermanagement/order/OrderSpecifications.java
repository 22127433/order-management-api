package com.example.ordermanagement.order;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

final class OrderSpecifications {
    private OrderSpecifications() { }

    /** ownerId is resolved by the service from the principal; null means ADMIN scope. */
    static Specification<CustomerOrder> matching(OrderListQuery search, Long ownerId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (ownerId != null) {
                predicates.add(builder.equal(root.get("user").get("id"), ownerId));
            }
            if (search.status() != null) {
                predicates.add(builder.equal(root.get("status"), search.status()));
            }
            if (search.from() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), search.from()));
            }
            if (search.to() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), search.to()));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
