package com.example.ordermanagement.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RestockRequest(@NotNull @Positive Integer quantity, @NotBlank @Size(max = 500) String reason) {
    public RestockRequest {
        reason = reason == null ? null : reason.strip();
    }
}
