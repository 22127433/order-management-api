package com.example.ordermanagement.product;

import com.example.ordermanagement.common.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stockQuantity;

    @Column(nullable = false)
    private boolean active = true;

    protected Product() { }

    public Product(String name, String description, BigDecimal price, int stockQuantity) {
        if (stockQuantity < 0) {
            throw new IllegalArgumentException("Invalid product data");
        }
        updateMetadata(name, description, price);
        this.stockQuantity = stockQuantity;
    }

    public void updateMetadata(String name, String description, BigDecimal price) {
        if (name == null || name.isBlank() || name.length() > 120
                || (description != null && description.length() > 2000)
                || price == null || price.signum() <= 0 || price.precision() - price.scale() > 12) {
            throw new IllegalArgumentException("Invalid product data");
        }
        BigDecimal normalizedPrice = price.setScale(2, RoundingMode.UNNECESSARY);
        this.name = name.strip();
        this.description = description;
        this.price = normalizedPrice;
    }

    public void ensureStockAvailable(int quantity) {
        requirePositiveQuantity(quantity);
        if (stockQuantity < quantity) {
            throw new ConflictException("Insufficient stock for product " + id
                    + ": requested " + quantity + ", available " + stockQuantity);
        }
    }

    public void ensureActive() {
        if (!active) {
            throw new ConflictException("Product " + id + " is inactive and cannot be ordered");
        }
    }

    public void changeAvailability(boolean active) {
        this.active = active;
    }

    public void deductStock(int quantity) {
        ensureStockAvailable(quantity);
        stockQuantity -= quantity;
    }

    public void ensureStockCanBeRestored(int quantity) {
        requirePositiveQuantity(quantity);
        if ((long) stockQuantity + quantity > Integer.MAX_VALUE) {
            throw new ConflictException("Restoring stock would exceed the limit for product " + id);
        }
    }

    public void restoreStock(int quantity) {
        ensureStockCanBeRestored(quantity);
        stockQuantity += quantity;
    }

    public void adjustStock(int delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("Stock adjustment delta must not be zero");
        }
        long adjusted = (long) stockQuantity + delta;
        if (adjusted < 0) {
            throw new ConflictException("Stock adjustment would make stock negative for product " + id);
        }
        if (adjusted > Integer.MAX_VALUE) {
            throw new ConflictException("Stock adjustment would exceed the limit for product " + id);
        }
        stockQuantity = (int) adjusted;
    }

    private void requirePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public int getStockQuantity() { return stockQuantity; }
    public boolean isActive() { return active; }
}
