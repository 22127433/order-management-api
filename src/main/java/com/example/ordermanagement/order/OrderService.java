package com.example.ordermanagement.order;

import com.example.ordermanagement.common.ResourceNotFoundException;
import com.example.ordermanagement.common.PageResponse;
import com.example.ordermanagement.inventory.StockMovement;
import com.example.ordermanagement.inventory.StockMovementRepository;
import com.example.ordermanagement.product.Product;
import com.example.ordermanagement.product.ProductRepository;
import com.example.ordermanagement.auth.UserRepository;
import com.example.ordermanagement.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
public class OrderService {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final CurrentUser currentUser;
    private final UserRepository users;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository,
                        StockMovementRepository stockMovementRepository, CurrentUser currentUser, UserRepository users) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.currentUser = currentUser;
        this.users = users;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Map<Long, Integer> quantities = OrderRequestNormalizer.quantities(request);

        // Every inventory operation locks products in ascending id order to avoid
        // opposite lock acquisition when two orders contain the same products.
        Map<Long, Product> products = lockProducts(quantities);
        quantities.forEach((id, quantity) -> {
            Product product = products.get(id);
            product.ensureStockAvailable(quantity);
        });

        CustomerOrder order = CustomerOrder.create(users.getReferenceById(currentUser.id()));
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

    public PageResponse<OrderSummaryResponse> findAll(OrderListQuery search) {
        Long ownerId = currentUser.isAdmin() ? null : currentUser.id();
        return PageResponse.from(orderRepository.findBy(OrderSpecifications.matching(search, ownerId),
                query -> query.as(OrderSummaryResponse.class).page(search.pageable())));
    }

    public OrderResponse getById(Long id) {
        return OrderResponse.from((currentUser.isAdmin() ? orderRepository.findById(id)
                : orderRepository.findByIdAndUserId(id, currentUser.id()))
                .orElseThrow(() -> new ResourceNotFoundException("Order", id)));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponse confirm(Long id) {
        CustomerOrder order = findForUpdate(id);
        order.confirm();
        return OrderResponse.from(order);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponse ship(Long id) {
        CustomerOrder order = findForUpdate(id);
        order.ship();
        return OrderResponse.from(order);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponse deliver(Long id) {
        CustomerOrder order = findForUpdate(id);
        order.deliver();
        return OrderResponse.from(order);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
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
