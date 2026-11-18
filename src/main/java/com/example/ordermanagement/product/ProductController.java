package com.example.ordermanagement.product;

import com.example.ordermanagement.common.PageResponse;
import com.example.ordermanagement.inventory.RestockRequest;
import com.example.ordermanagement.inventory.StockMovementQuery;
import com.example.ordermanagement.inventory.StockMovementResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
@Validated
@ApiResponses({
        @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized"),
        @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
        @ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest"),
        @ApiResponse(responseCode = "500", ref = "#/components/responses/InternalError")
})
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @Operation(tags = "Products", summary = "Create a product", description = "ADMIN only. Records INITIAL_STOCK in the same transaction. "
            + "The application never loads demo data automatically.")
    @ApiResponse(responseCode = "201", description = "Created product", useReturnTypeSchema = true,
            headers = @Header(name = "Location", description = "Created product URL", schema = @Schema(type = "string")))
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse result = productService.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + result.id())).body(result);
    }

    @GetMapping
    @SecurityRequirements
    @Operation(tags = "Products", summary = "Search and paginate products", description = "All filters are optional and combined with AND. "
            + "Filtering, counting, sorting, and pagination run in PostgreSQL. Price bounds are inclusive and nonnegative; "
            + "minPrice must not exceed maxPrice. page * size must fit a signed 32-bit integer. "
            + "Non-ID sorts append ID ascending as a stable tie-breaker.")
    @ApiResponse(responseCode = "200", description = "Filtered product page", useReturnTypeSchema = true)
    public PageResponse<ProductResponse> findAll(
            @Parameter(description = "Case-insensitive literal name substring; trimmed, at most 120 characters", example = "keyboard",
                    schema = @Schema(maxLength = 120))
            @RequestParam(required = false) String keyword,
            @Parameter(description = "Inclusive lower price bound", example = "10.00", schema = @Schema(minimum = "0"))
            @RequestParam(required = false) BigDecimal minPrice,
            @Parameter(description = "Inclusive upper price bound", example = "100.00", schema = @Schema(minimum = "0"))
            @RequestParam(required = false) BigDecimal maxPrice,
            @Parameter(description = "true: stock > 0; false: stock = 0; omitted: either")
            @RequestParam(required = false) Boolean inStock,
            @Parameter(description = "Zero-based page number")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size from 1 to 100")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "field[,asc|desc]; fields: id, name, price, stockQuantity; omitted direction is asc",
                    example = "price,asc")
            @RequestParam(defaultValue = "id,asc") String sort) {
        return productService.findAll(new ProductSearchQuery(keyword, minPrice, maxPrice, inStock, page, size, sort));
    }

    @GetMapping("/{id}")
    @SecurityRequirements
    @Operation(tags = "Products", summary = "Get a product")
    @ApiResponse(responseCode = "200", description = "Product details", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
    public ProductResponse getById(@PathVariable @Positive Long id) {
        return productService.getById(id);
    }

    @PutMapping("/{id}")
    @Operation(tags = "Products", summary = "Replace product metadata", description = "ADMIN only. Requires the current stockQuantity unchanged; "
            + "a different stock value returns 409. Use restock to add inventory. Existing order price snapshots are preserved.")
    @ApiResponse(responseCode = "200", description = "Updated product", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
    public ProductResponse update(@PathVariable @Positive Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @PostMapping("/{id}/restock")
    @Operation(tags = "Inventory", summary = "Restock a product", description = "ADMIN only. Adds a positive quantity and a MANUAL_RESTOCK movement "
            + "atomically under a product write lock. Stock overflow returns 409. This endpoint does not support Idempotency-Key; "
            + "repeating a successful call adds stock again.")
    @ApiResponse(responseCode = "200", description = "Product with updated stock", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
    public ProductResponse restock(@PathVariable @Positive Long id, @Valid @RequestBody RestockRequest request) {
        return productService.restock(id, request);
    }

    @GetMapping("/{id}/stock-movements")
    @Operation(tags = "Inventory", summary = "Get paginated inventory history", description = "ADMIN only. Product-scoped history, newest first by default. "
            + "Sorts timestamp ties by ID in the same direction. Legacy products can have no history: no backfill is performed. "
            + "page * size must fit a signed 32-bit integer.")
    @ApiResponse(responseCode = "200", description = "Inventory movement page", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
    public PageResponse<StockMovementResponse> stockMovements(@PathVariable @Positive Long id,
            @Parameter(description = "Zero-based page number")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size from 1 to 100")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "Only createdAt[,asc|desc]; omitted direction is desc", example = "createdAt,desc")
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return productService.stockMovements(id, new StockMovementQuery(page, size, sort));
    }

    @DeleteMapping("/{id}")
    @Operation(tags = "Products", summary = "Delete an unreferenced legacy product", description = "ADMIN only. Returns 409 when any order item or inventory "
            + "movement references the product. Newly created products have INITIAL_STOCK history and cannot be deleted.")
    @ApiResponse(responseCode = "204", description = "Product deleted; empty response")
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
    public ResponseEntity<Void> delete(@PathVariable @Positive Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
