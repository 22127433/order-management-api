package com.example.ordermanagement.inventory;

import com.example.ordermanagement.common.BadRequestException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Signed, nonzero stock adjustment. Resulting stock must be between 0 and 2147483647. "
        + "Not idempotent: repeating a successful request applies the delta again.")
public record AdjustStockRequest(
        @Schema(example = "-3", minimum = "-2147483648", maximum = "2147483647",
                description = "Signed 32-bit integer; positive adds stock, negative removes stock; zero is invalid")
        @NotNull Integer delta,
        @Schema(example = "Damaged products", minLength = 1, description = "Required, trimmed, nonblank reason")
        @NotBlank @Size(max = 500) String reason) {
    public AdjustStockRequest {
        if (delta != null && delta == 0) {
            throw new BadRequestException("delta must not be zero");
        }
        reason = reason == null ? null : reason.strip();
    }
}
