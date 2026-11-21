package com.example.ordermanagement.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "ADMIN-only sale availability. Does not change inventory or create a stock movement.")
public record ProductAvailabilityRequest(@NotNull @Schema(example = "false", description = "true to sell, false to stop selling")
                                         Boolean active) { }
