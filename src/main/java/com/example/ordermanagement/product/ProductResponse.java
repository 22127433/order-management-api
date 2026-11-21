package com.example.ordermanagement.product;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Product catalog data and current inventory quantity")
public record ProductResponse(@Schema(example = "1") Long id, @Schema(example = "Mechanical Keyboard") String name,
                               @Schema(nullable = true, example = "USB keyboard") String description,
                               @Schema(example = "59.90", description = "Current catalog price")
                               BigDecimal price, @Schema(example = "20", minimum = "0") int stockQuantity,
                               @Schema(example = "true", description = "Sale availability; public reads only return active products") boolean active) {
    public ProductResponse(Long id, String name, String description, BigDecimal price, int stockQuantity) {
        this(id, name, description, price, stockQuantity, true);
    }

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStockQuantity(), product.isActive());
    }
}
