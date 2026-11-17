package com.example.ordermanagement.order;

import com.example.ordermanagement.common.BadRequestException;
import java.util.Map;
import java.util.TreeMap;

/** Shared service-level validation and deterministic item ordering. */
final class OrderRequestNormalizer {
    private OrderRequestNormalizer() { }

    static Map<Long, Integer> quantities(CreateOrderRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()
                || request.items().size() > 100) {
            throw new BadRequestException("An order must contain between 1 and 100 items");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        for (CreateOrderRequest.Item item : request.items()) {
            if (item == null || item.productId() == null || item.productId() <= 0) {
                throw new BadRequestException("Each item must have a positive productId");
            }
            if (item.quantity() == null || item.quantity() <= 0 || item.quantity() > 1_000_000) {
                throw new BadRequestException("Item quantity must be between 1 and 1000000");
            }
            if (quantities.putIfAbsent(item.productId(), item.quantity()) != null) {
                throw new BadRequestException("Duplicate productId: " + item.productId());
            }
        }
        return quantities;
    }

    static String canonicalPayload(CreateOrderRequest request) {
        StringBuilder result = new StringBuilder("v1|");
        quantities(request).forEach((id, quantity) -> result.append(id).append(':').append(quantity).append(';'));
        return result.toString();
    }
}
