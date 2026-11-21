package com.example.ordermanagement.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "Product creation with initial inventory. PUT uses UpdateProductRequest instead.")
public record ProductRequest(
        @Schema(example = "Mechanical Keyboard", minLength = 1, description = "Required nonblank name")
        @NotBlank @Size(max = 120) String name,
        @Schema(example = "USB keyboard", nullable = true, description = "Optional description")
        @Size(max = 2000) String description,
        @Schema(example = "59.90", minimum = "0.01", maximum = "999999999999.99", multipleOf = 0.01,
                description = "Positive decimal; at most 12 integer digits and 2 fractional digits")
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal price,
        @Schema(example = "20", maximum = "2147483647", description = "Initial stock on POST only")
        @NotNull @Min(0) Integer stockQuantity) { }
