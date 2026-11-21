package com.example.ordermanagement.order;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Order list projection; no items or user entity are loaded. Use GET /api/orders/{id} for details.")
public record OrderSummaryResponse(@Schema(example = "1") Long id, OrderStatus status,
        @Schema(example = "119.80", description = "Persisted original order total") BigDecimal totalAmount,
        @Schema(example = "2026-11-21T10:00:00Z") Instant createdAt) { }
