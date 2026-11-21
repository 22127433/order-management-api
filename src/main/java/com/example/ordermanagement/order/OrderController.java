package com.example.ordermanagement.order;

import com.example.ordermanagement.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.net.URI;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Validated
@Tag(name = "Orders")
@ApiResponses({
        @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized"),
        @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
        @ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest"),
        @ApiResponse(responseCode = "500", ref = "#/components/responses/InternalError")
})
public class OrderController {
    private final OrderService orderService;

    private final IdempotentOrderService idempotentOrders;

    public OrderController(OrderService orderService, IdempotentOrderService idempotentOrders) {
        this.orderService = orderService;
        this.idempotentOrders = idempotentOrders;
    }

    @PostMapping
    @Operation(summary = "Create an order", description = "CUSTOMER or ADMIN. Owner comes from the authenticated principal; client userId is rejected. "
            + "Accepts 1–100 distinct products, each with quantity 1–1000000. "
            + "Locks products by ascending ID, rejects inactive products with 409, calculates BigDecimal price snapshots, and deducts stock with inventory movements "
            + "in one transaction. An optional Idempotency-Key makes retries durable in PostgreSQL: equivalent payloads replay the "
            + "original creation snapshot, even after the order changes state. Reordering items does not change payload identity. "
            + "A different valid payload using a committed key returns 409. Concurrent callers wait for the key owner; lock timeout "
            + "returns 409 for retry. Failed transactions do not consume keys. Keys are scoped per authenticated user and retained indefinitely. "
            + "Without the header, each successful call creates another order; no universal exactly-once guarantee is claimed.")
    @ApiResponse(responseCode = "201", description = "New order created", useReturnTypeSchema = true,
            headers = {
                    @Header(name = "Location", description = "Created order URL", schema = @Schema(type = "string")),
                    @Header(name = "Idempotency-Replayed", description = "false for new keyed requests; absent without a key",
                            schema = @Schema(type = "boolean"))
            })
    @ApiResponse(responseCode = "200", description = "Existing creation response replayed; use GET for current order state",
            useReturnTypeSchema = true, headers = {
                    @Header(name = "Location", description = "Existing order URL", schema = @Schema(type = "string")),
                    @Header(name = "Idempotency-Replayed", description = "true on replay", schema = @Schema(type = "boolean"))
            })
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
            @Parameter(description = "Optional, case-sensitive client-generated key. Reuse with the same payload for retries. "
                    + "Exactly one header value; no spaces. Suggested value: a fresh UUID per intended order.",
                    schema = @Schema(minLength = 1, maxLength = 128, pattern = "^[A-Za-z0-9._:-]{1,128}$"))
            @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        if (key != null) {
            OrderCreationResult result = idempotentOrders.create(key, request);
            return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                    .location(URI.create("/api/orders/" + result.response().id()))
                    .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                    .body(result.response());
        }
        OrderResponse result = orderService.create(request);
        return ResponseEntity.created(URI.create("/api/orders/" + result.id())).body(result);
    }

    @GetMapping
    @Operation(summary = "List orders", description = "CUSTOMER sees only its own orders; ADMIN sees all, including legacy orders without an owner. "
            + "Ownership comes from the principal, never a client userId. Optional filters combine with AND; from/to are inclusive ISO-8601 instants "
            + "with UTC Z or a numeric offset. from must not be after to. Database pagination uses fixed createdAt DESC, id DESC; "
            + "page is zero-based, size is 1-100 and page*size must fit a signed int. Summaries omit items; use GET /api/orders/{id} for details.")
    @ApiResponse(responseCode = "200", description = "Filtered page of order summaries; an empty page is valid", useReturnTypeSchema = true)
    public PageResponse<OrderSummaryResponse> findAll(
            @Parameter(description = "Optional exact lifecycle status; case-sensitive")
            @RequestParam(required = false) OrderStatus status,
            @Parameter(description = "Inclusive createdAt lower bound; must include a timezone", example = "2026-11-01T00:00:00Z",
                    schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Inclusive createdAt upper bound; must include a timezone", example = "2026-11-30T23:59:59Z",
                    schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Zero-based page number")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size from 1 to 100")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orderService.findAll(new OrderListQuery(status, from, to, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order", description = "CUSTOMER: own orders only; other users' orders return 404. ADMIN: any order, "
            + "including legacy unowned orders. Returns current lifecycle status and original monetary snapshots.")
    @ApiResponse(responseCode = "200", description = "Order with items", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
    public OrderResponse getById(@PathVariable @Positive Long id) {
        return orderService.getById(id);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm an order", description = "ADMIN only. Only CREATED → CONFIRMED. Locks the order; does not deduct stock again. "
            + "Repeated or invalid transitions return 409.")
    @ApiResponse(responseCode = "200", description = "Confirmed order", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
    public OrderResponse confirm(@PathVariable @Positive Long id) {
        return orderService.confirm(id);
    }

    @PostMapping("/{id}/ship")
    @Operation(summary = "Mark an order as shipped", description = "ADMIN only. Only CONFIRMED → SHIPPED. Records handover state, "
            + "without a carrier integration or additional stock deduction. Repeated/invalid transitions return 409.")
    @ApiResponse(responseCode = "200", description = "Shipped order", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
    public OrderResponse ship(@PathVariable @Positive Long id) {
        return orderService.ship(id);
    }

    @PostMapping("/{id}/deliver")
    @Operation(summary = "Mark an order as delivered", description = "ADMIN only. Only SHIPPED → DELIVERED. No stock change. "
            + "DELIVERED is terminal; repeated/invalid transitions return 409.")
    @ApiResponse(responseCode = "200", description = "Delivered order", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
    public OrderResponse deliver(@PathVariable @Positive Long id) {
        return orderService.deliver(id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a newly created order", description = "ADMIN only. Only CREATED → CANCELLED. Locks the order first, "
            + "then products by ascending ID. Stock restoration, cancellation movements, and state change commit together. "
            + "Cancellation restores stock once; repeated cancellation or cancelling any other state returns 409.")
    @ApiResponse(responseCode = "200", description = "Cancelled order", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
    public OrderResponse cancel(@PathVariable @Positive Long id) {
        return orderService.cancel(id);
    }
}
