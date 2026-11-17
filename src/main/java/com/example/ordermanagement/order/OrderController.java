package com.example.ordermanagement.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {
    private final OrderService orderService;

    private final IdempotentOrderService idempotentOrders;

    public OrderController(OrderService orderService, IdempotentOrderService idempotentOrders) {
        this.orderService = orderService;
        this.idempotentOrders = idempotentOrders;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
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

    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable @Positive Long id) {
        return orderService.getById(id);
    }

    @PostMapping("/{id}/confirm")
    public OrderResponse confirm(@PathVariable @Positive Long id) {
        return orderService.confirm(id);
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@PathVariable @Positive Long id) {
        return orderService.ship(id);
    }

    @PostMapping("/{id}/deliver")
    public OrderResponse deliver(@PathVariable @Positive Long id) {
        return orderService.deliver(id);
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable @Positive Long id) {
        return orderService.cancel(id);
    }
}
