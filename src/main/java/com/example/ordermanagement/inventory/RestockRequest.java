package com.example.ordermanagement.inventory;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "Manual stock increase. Resulting stock must not exceed 2147483647; no idempotency support for restock.")
public record RestockRequest(@Schema(example = "5", maximum = "2147483647") @NotNull @Positive Integer quantity,
                             @Schema(example = "Supplier delivery", minLength = 1, description = "Required, trimmed, nonblank reason")
                             @NotBlank @Size(max = 500) String reason) {
    public RestockRequest {
        reason = reason == null ? null : reason.strip();
    }
}
