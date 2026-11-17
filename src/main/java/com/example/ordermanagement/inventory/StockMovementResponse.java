package com.example.ordermanagement.inventory;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Immutable historical inventory snapshot; before + delta = after")
public record StockMovementResponse(@Schema(example = "1") Long id, @Schema(example = "1") Long productId,
                                    StockMovementType type, @Schema(example = "5", description = "Signed inventory change") int quantityDelta,
                                    @Schema(example = "20") int stockBefore, @Schema(example = "25") int stockAfter,
                                    @Schema(nullable = true, description = "Present for order deduction/cancellation only") Long referenceOrderId,
                                    @Schema(nullable = true, example = "Supplier delivery", description = "Present for manual restock only")
                                    String reason, @Schema(example = "2026-11-17T10:00:00Z") Instant createdAt) {
    public static StockMovementResponse from(StockMovement movement) {
        return new StockMovementResponse(movement.getId(), movement.getProduct().getId(), movement.getType(),
                movement.getQuantityDelta(), movement.getStockBefore(), movement.getStockAfter(),
                movement.getReferenceOrder() == null ? null : movement.getReferenceOrder().getId(),
                movement.getReason(), movement.getCreatedAt());
    }
}
