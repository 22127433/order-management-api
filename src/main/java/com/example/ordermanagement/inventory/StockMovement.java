package com.example.ordermanagement.inventory;

import com.example.ordermanagement.order.CustomerOrder;
import com.example.ordermanagement.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "stock_movements")
@Immutable
public class StockMovement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30, updatable = false)
    private StockMovementType type;

    @Column(nullable = false, updatable = false)
    private int quantityDelta;

    @Column(nullable = false, updatable = false)
    private int stockBefore;

    @Column(nullable = false, updatable = false)
    private int stockAfter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reference_order_id", updatable = false)
    private CustomerOrder referenceOrder;

    @Column(length = 500, updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected StockMovement() { }

    private StockMovement(Product product, StockMovementType type, int delta, int before,
                          CustomerOrder order, String reason) {
        if (product == null || before < 0 || (long) before + delta != product.getStockQuantity()) {
            throw new IllegalArgumentException("Movement must match the stock change");
        }
        this.product = product;
        this.type = type;
        this.quantityDelta = delta;
        this.stockBefore = before;
        this.stockAfter = product.getStockQuantity();
        this.referenceOrder = order;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public static StockMovement initial(Product product) {
        return new StockMovement(product, StockMovementType.INITIAL_STOCK,
                product.getStockQuantity(), 0, null, null);
    }

    public static StockMovement deduction(Product product, CustomerOrder order, int quantity, int before) {
        requireOrderAndQuantity(order, quantity);
        return new StockMovement(product, StockMovementType.ORDER_DEDUCTION, -quantity, before, order, null);
    }

    public static StockMovement cancellation(Product product, CustomerOrder order, int quantity, int before) {
        requireOrderAndQuantity(order, quantity);
        return new StockMovement(product, StockMovementType.ORDER_CANCELLATION, quantity, before, order, null);
    }

    public static StockMovement restock(Product product, int quantity, int before, String reason) {
        if (quantity <= 0 || reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new IllegalArgumentException("Restock requires positive quantity and a reason of 1 to 500 characters");
        }
        return new StockMovement(product, StockMovementType.MANUAL_RESTOCK, quantity, before, null, reason.strip());
    }

    private static void requireOrderAndQuantity(CustomerOrder order, int quantity) {
        if (order == null || quantity <= 0) {
            throw new IllegalArgumentException("Order movements require an order and positive quantity");
        }
    }

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public StockMovementType getType() { return type; }
    public int getQuantityDelta() { return quantityDelta; }
    public int getStockBefore() { return stockBefore; }
    public int getStockAfter() { return stockAfter; }
    public CustomerOrder getReferenceOrder() { return referenceOrder; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
