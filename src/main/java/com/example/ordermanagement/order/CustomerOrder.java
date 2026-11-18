package com.example.ordermanagement.order;

import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.auth.AppUser;
import com.example.ordermanagement.product.Product;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "customer_orders")
public class CustomerOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, precision = 25, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Nullable only for data created before authentication was introduced; no cascade to users.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    private AppUser user;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    protected CustomerOrder() { }

    public static CustomerOrder create(AppUser owner) {
        if (owner == null) { throw new IllegalArgumentException("New orders require an owner"); }
        CustomerOrder order = new CustomerOrder();
        order.user = owner;
        order.status = OrderStatus.CREATED;
        order.totalAmount = new BigDecimal("0.00");
        order.createdAt = Instant.now();
        return order;
    }

    public void addItem(Product product, int quantity) {
        if (id != null || status != OrderStatus.CREATED) {
            throw new IllegalStateException("Items can only be added to a new order");
        }
        OrderItem item = new OrderItem(this, product, quantity);
        items.add(item);
        totalAmount = totalAmount.add(item.getSubtotal());
    }

    public void ensureCancellable() {
        if (status == OrderStatus.CANCELLED) {
            throw new ConflictException("Order " + id + " is already cancelled");
        }
        ensureTransitionAllowed(OrderStatus.CANCELLED);
    }

    public void confirm() {
        transitionTo(OrderStatus.CONFIRMED);
    }

    public void ship() {
        transitionTo(OrderStatus.SHIPPED);
    }

    public void deliver() {
        transitionTo(OrderStatus.DELIVERED);
    }

    public void cancel() {
        ensureCancellable();
        status = OrderStatus.CANCELLED;
    }

    private void transitionTo(OrderStatus target) {
        ensureTransitionAllowed(target);
        status = target;
    }

    private void ensureTransitionAllowed(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException("Order " + id + " cannot transition from " + status + " to " + target);
        }
    }

    public Long getId() { return id; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public AppUser getUser() { return user; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
}
