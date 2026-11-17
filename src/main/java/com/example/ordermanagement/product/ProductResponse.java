package com.example.ordermanagement.product;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Product catalog data and current inventory quantity")
public record ProductResponse(@Schema(example = "1") Long id, @Schema(example = "Mechanical Keyboard") String name,
                               @Schema(nullable = true, example = "USB keyboard") String description,
                               @Schema(example = "59.90", description = "Current catalog price")
                               BigDecimal price, @Schema(example = "20", minimum = "0") int stockQuantity) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStockQuantity());
    }
}
