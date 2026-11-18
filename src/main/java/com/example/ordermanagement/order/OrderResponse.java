package com.example.ordermanagement.order;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "Order with original financial snapshots. Keyed creation retries return the original creation response; "
        + "GET returns the current status.")
public record OrderResponse(@Schema(example = "1") Long id, OrderStatus status,
                             @Schema(example = "119.80", description = "Sum of item subtotals, computed with BigDecimal") BigDecimal totalAmount,
                             @Schema(example = "2026-11-17T10:00:00Z") Instant createdAt, List<Item> items,
                             @Schema(nullable = true, description = "Owner ID; null only for legacy orders") Long userId) {
    public OrderResponse(Long id, OrderStatus status, BigDecimal totalAmount, Instant createdAt, List<Item> items) {
        this(id, status, totalAmount, createdAt, items, null);
    }
    @Schema(name = "OrderItemResponse", description = "Persisted quantity and monetary snapshots; unrelated to the request item schema")
    public record Item(@Schema(example = "1") Long id, @Schema(example = "1") Long productId,
                       @Schema(example = "2") int quantity, @Schema(example = "59.90") BigDecimal unitPrice,
                       @Schema(example = "119.80", description = "unitPrice × quantity") BigDecimal subtotal) { }

    public static OrderResponse from(CustomerOrder order) {
        List<Item> items = order.getItems().stream()
                .map(i -> new Item(i.getId(), i.getProduct().getId(), i.getQuantity(),
                        i.getUnitPrice(), i.getSubtotal())).toList();
        return new OrderResponse(order.getId(), order.getStatus(), order.getTotalAmount(),
                order.getCreatedAt(), items, order.getUser() == null ? null : order.getUser().getId());
    }
}
