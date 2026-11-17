package com.example.ordermanagement.common;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Shared API error format. fieldErrors is empty for errors without field-level violations. "
        + "Unexpected 500 errors do not expose exception details.")
public record ApiError(Instant timestamp,
                       @Schema(example = "400") int status, @Schema(example = "Bad Request") String error,
                       @Schema(example = "Request validation failed") String message,
                       String path, List<FieldError> fieldErrors) {
    @Schema(name = "ApiFieldError", description = "A rejected field/path and its validation message")
    public record FieldError(String field, String message) { }
}
