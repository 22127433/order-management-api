package com.example.ordermanagement.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Distinct product IDs and quantities; the server supplies all prices and totals")
public record CreateOrderRequest(
        @Schema(description = "1–100 non-null items; duplicate product IDs return 400")
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items) {
    @Schema(name = "CreateOrderItem", description = "One distinct product to purchase")
    public record Item(@Schema(example = "1") @NotNull @Positive Long productId,
                       @Schema(example = "2") @NotNull @Positive @Max(1_000_000) Integer quantity) { }
}
