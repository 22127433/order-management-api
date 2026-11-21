package com.example.ordermanagement.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "Product metadata replacement only. stockQuantity and active are unknown fields and return 400.")
public record UpdateProductRequest(
        @Schema(example = "Mechanical Keyboard", minLength = 1, description = "Required nonblank name")
        @NotBlank @Size(max = 120) String name,
        @Schema(example = "USB keyboard", nullable = true, description = "Optional description; omitted/null clears it")
        @Size(max = 2000) String description,
        @Schema(example = "59.90", minimum = "0.01", maximum = "999999999999.99", multipleOf = 0.01,
                description = "Positive decimal; at most 12 integer digits and 2 fractional digits")
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal price) { }
