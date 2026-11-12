package com.example.ordermanagement.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(Long id, OrderStatus status, BigDecimal totalAmount,
                             Instant createdAt, List<Item> items) {
    public record Item(Long id, Long productId, int quantity, BigDecimal unitPrice, BigDecimal subtotal) { }

    public static OrderResponse from(CustomerOrder order) {
        List<Item> items = order.getItems().stream()
                .map(i -> new Item(i.getId(), i.getProduct().getId(), i.getQuantity(),
                        i.getUnitPrice(), i.getSubtotal())).toList();
        return new OrderResponse(order.getId(), order.getStatus(), order.getTotalAmount(),
                order.getCreatedAt(), items);
    }
}
