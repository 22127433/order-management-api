package com.example.ordermanagement.inventory;

import java.time.Instant;

public record StockMovementResponse(Long id, Long productId, StockMovementType type, int quantityDelta,
                                    int stockBefore, int stockAfter, Long referenceOrderId,
                                    String reason, Instant createdAt) {
    public static StockMovementResponse from(StockMovement movement) {
        return new StockMovementResponse(movement.getId(), movement.getProduct().getId(), movement.getType(),
                movement.getQuantityDelta(), movement.getStockBefore(), movement.getStockAfter(),
                movement.getReferenceOrder() == null ? null : movement.getReferenceOrder().getId(),
                movement.getReason(), movement.getCreatedAt());
    }
}
