package com.example.ordermanagement.order;

import com.example.ordermanagement.common.BadRequestException;
import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
public class IdempotentOrderService {
    private final OrderService orders;
    private final OrderIdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;
    private final CurrentUser currentUser;

    public IdempotentOrderService(OrderService orders, OrderIdempotencyRepository idempotency,
                                 ObjectMapper objectMapper, CurrentUser currentUser) {
        this.orders = orders;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.currentUser = currentUser;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderCreationResult create(String key, CreateOrderRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new BadRequestException("Idempotency-Key must contain 1 to 128 ASCII letters, digits, '.', '_', ':', or '-'");
        }
        String payload = OrderRequestNormalizer.canonicalPayload(request);
        Long userId = currentUser.id();
        try {
            if (!idempotency.claim(userId, key, payload)) {
                var stored = idempotency.findCompleted(userId, key);
                if (!stored.canonicalPayload().equals(payload)) {
                    throw new ConflictException("Idempotency-Key was already used with a different order payload");
                }
                return new OrderCreationResult(objectMapper.readValue(stored.responseBody(), OrderResponse.class), true);
            }

            // A call through another Spring bean: REQUIRED joins this transaction, including its JDBC writes.
            OrderResponse response = orders.create(request);
            idempotency.complete(userId, key, response.id(), objectMapper.writeValueAsString(response));
            return new OrderCreationResult(response, false);
        } catch (JsonProcessingException exception) {
            // Runtime failure rolls back reservation, order, inventory, and movements together.
            throw new IllegalStateException("Cannot encode or decode the stored order response", exception);
        }
    }
}
