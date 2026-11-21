package com.example.ordermanagement.product;

import com.example.ordermanagement.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/products")
@Validated
@Tag(name = "Products")
@ApiResponses({@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest"),
        @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized"),
        @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
        @ApiResponse(responseCode = "500", ref = "#/components/responses/InternalError")})
public class AdminProductController {
    private final ProductService products;

    public AdminProductController(ProductService products) { this.products = products; }

    @GetMapping
    @Operation(summary = "Search the ADMIN product catalog", description = "ADMIN only. active=true selects active, false selects inactive, "
            + "omitted selects both. Other filters/pagination/sort follow the public catalog contract and combine with AND in PostgreSQL. "
            + "Includes active in each ProductResponse. page*size must fit an int; minPrice must not exceed maxPrice.")
    @ApiResponse(responseCode = "200", description = "Filtered ADMIN product page", useReturnTypeSchema = true)
    public PageResponse<ProductResponse> findAll(
            @Parameter(description = "Optional sale availability; omitted includes both") @RequestParam(required = false) Boolean active,
            @Parameter(description = "Literal case-insensitive name substring, trimmed, at most 120 characters", schema = @Schema(maxLength = 120))
            @RequestParam(required = false) String keyword,
            @Parameter(description = "Inclusive nonnegative price minimum", schema = @Schema(minimum = "0"))
            @RequestParam(required = false) BigDecimal minPrice,
            @Parameter(description = "Inclusive nonnegative price maximum", schema = @Schema(minimum = "0"))
            @RequestParam(required = false) BigDecimal maxPrice,
            @Parameter(description = "true: stock > 0; false: stock = 0; omitted: either") @RequestParam(required = false) Boolean inStock,
            @Parameter(description = "Zero-based page") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size 1-100") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "field[,asc|desc]; id, name, price, stockQuantity. Non-ID sorts append ID ascending.")
            @RequestParam(defaultValue = "id,asc") String sort) {
        return products.findAllForAdmin(new ProductSearchQuery(keyword, minPrice, maxPrice, inStock, page, size, sort), active);
    }
}
