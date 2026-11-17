package com.example.ordermanagement.order;

import com.example.ordermanagement.common.BadRequestException;
import com.example.ordermanagement.common.ResourceNotFoundException;
import com.example.ordermanagement.product.Product;
import com.example.ordermanagement.product.ProductRepository;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()
                || request.items().size() > 100) {
            throw new BadRequestException("An order must contain between 1 and 100 items");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        for (CreateOrderRequest.Item item : request.items()) {
            if (item == null || item.productId() == null || item.productId() <= 0) {
                throw new BadRequestException("Each item must have a positive productId");
            }
            if (item.quantity() == null || item.quantity() <= 0 || item.quantity() > 1_000_000) {
                throw new BadRequestException("Item quantity must be between 1 and 1000000");
            }
            if (quantities.putIfAbsent(item.productId(), item.quantity()) != null) {
                throw new BadRequestException("Duplicate productId: " + item.productId());
            }
        }

        // Every inventory operation locks products in ascending id order to avoid
        // opposite lock acquisition when two orders contain the same products.
        Map<Long, Product> products = lockProducts(quantities);
        quantities.forEach((id, quantity) -> products.get(id).ensureStockAvailable(quantity));

        CustomerOrder order = CustomerOrder.create();
        quantities.forEach((id, quantity) -> {
            Product product = products.get(id);
            product.deductStock(quantity);
            order.addItem(product, quantity);
        });
        return OrderResponse.from(orderRepository.save(order));
    }

    public OrderResponse getById(Long id) {
        return OrderResponse.from(orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id)));
    }

    @Transactional
    public OrderResponse confirm(Long id) {
        CustomerOrder order = findForUpdate(id);
        order.confirm();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse ship(Long id) {
        CustomerOrder order = findForUpdate(id);
        order.ship();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse deliver(Long id) {
        CustomerOrder order = findForUpdate(id);
        order.deliver();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse cancel(Long id) {
        CustomerOrder order = findForUpdate(id);
        order.ensureCancellable();

        Map<Long, Integer> quantities = new TreeMap<>();
        order.getItems().forEach(item -> quantities.put(item.getProduct().getId(), item.getQuantity()));
        Map<Long, Product> products = lockProducts(quantities);
        quantities.forEach((productId, quantity) -> products.get(productId).ensureStockCanBeRestored(quantity));
        quantities.forEach((productId, quantity) -> products.get(productId).restoreStock(quantity));
        order.cancel();
        return OrderResponse.from(order);
    }

    private CustomerOrder findForUpdate(Long id) {
        // All transitions serialize on the order row before inspecting its status.
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
    }

    private Map<Long, Product> lockProducts(Map<Long, Integer> quantities) {
        Map<Long, Product> products = new TreeMap<>();
        for (Long id : quantities.keySet()) {
            products.put(id, productRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Product", id)));
        }
        return products;
    }
}
