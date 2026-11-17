package com.example.ordermanagement.order;

import com.example.ordermanagement.common.ResourceNotFoundException;
import com.example.ordermanagement.inventory.StockMovement;
import com.example.ordermanagement.inventory.StockMovementRepository;
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
    private final StockMovementRepository stockMovementRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository,
                        StockMovementRepository stockMovementRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Map<Long, Integer> quantities = OrderRequestNormalizer.quantities(request);

        // Every inventory operation locks products in ascending id order to avoid
        // opposite lock acquisition when two orders contain the same products.
        Map<Long, Product> products = lockProducts(quantities);
        quantities.forEach((id, quantity) -> products.get(id).ensureStockAvailable(quantity));

        CustomerOrder order = CustomerOrder.create();
        Map<Long, Integer> stockBefore = new TreeMap<>();
        quantities.forEach((id, quantity) -> {
            Product product = products.get(id);
            stockBefore.put(id, product.getStockQuantity());
            product.deductStock(quantity);
            order.addItem(product, quantity);
        });
        CustomerOrder saved = orderRepository.save(order);
        quantities.forEach((id, quantity) -> stockMovementRepository.save(
                StockMovement.deduction(products.get(id), saved, quantity, stockBefore.get(id))));
        return OrderResponse.from(saved);
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
        quantities.forEach((productId, quantity) -> {
            Product product = products.get(productId);
            int before = product.getStockQuantity();
            product.restoreStock(quantity);
            stockMovementRepository.save(StockMovement.cancellation(product, order, quantity, before));
        });
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
