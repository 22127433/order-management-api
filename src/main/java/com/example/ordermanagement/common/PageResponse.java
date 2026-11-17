package com.example.ordermanagement.common;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

@Schema(description = "Database-filtered page; totals refer to all matching rows")
public record PageResponse<T>(List<T> content, @Schema(example = "0", description = "Zero-based page") int page,
                              @Schema(example = "20") int size, @Schema(example = "2") long totalElements,
                              @Schema(example = "1") int totalPages, @Schema(example = "true") boolean last) {
    public static <T> PageResponse<T> from(Page<T> result) {
        return new PageResponse<>(List.copyOf(result.getContent()), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages(), result.isLast());
    }
}
