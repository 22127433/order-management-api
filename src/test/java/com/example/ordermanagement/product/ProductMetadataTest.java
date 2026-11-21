package com.example.ordermanagement.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductMetadataTest {
    @Test
    void constructorUsesMetadataNormalizationAndKeepsInitialStock() {
        Product product = new Product(" Keyboard ", null, BigDecimal.ONE, 10);
        assertThat(product.getName()).isEqualTo("Keyboard");
        assertThat(product.getPrice()).isEqualTo(new BigDecimal("1.00"));
        assertThat(product.getStockQuantity()).isEqualTo(10);
        assertThat(product.isActive()).isTrue();
    }

    @Test
    void constructorStillRejectsNegativeInitialStock() {
        assertThatThrownBy(() -> new Product("Keyboard", null, BigDecimal.ONE, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataChangesPreserveStockAfterInventoryOperationsAndInactiveFlag() {
        Product product = new Product("Original", "Old", BigDecimal.ONE, 10);
        product.deductStock(3);
        product.restoreStock(2);
        product.changeAvailability(false);
        product.updateMetadata(" Renamed ", null, new BigDecimal("2.50"));
        assertThat(product.getName()).isEqualTo("Renamed");
        assertThat(product.getDescription()).isNull();
        assertThat(product.getPrice()).isEqualByComparingTo("2.50");
        assertThat(product.getStockQuantity()).isEqualTo(9);
        assertThat(product.isActive()).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-1", "0", "1000000000000"})
    void invalidPriceLeavesMetadataAndStockUnchanged(String value) {
        Product product = new Product("Original", "Old", BigDecimal.ONE, 10);
        assertThatThrownBy(() -> product.updateMetadata("Changed", "New", value == null ? null : new BigDecimal(value)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getName()).isEqualTo("Original");
        assertThat(product.getDescription()).isEqualTo("Old");
        assertThat(product.getPrice()).isEqualByComparingTo("1.00");
        assertThat(product.getStockQuantity()).isEqualTo(10);
    }

    @Test
    void invalidFractionalPrecisionDoesNotPartiallyReplaceMetadata() {
        Product product = new Product("Original", "Old", BigDecimal.ONE, 10);
        assertThatThrownBy(() -> product.updateMetadata("Changed", "New", new BigDecimal("1.001")))
                .isInstanceOf(ArithmeticException.class);
        assertThat(product.getName()).isEqualTo("Original");
        assertThat(product.getDescription()).isEqualTo("Old");
        assertThat(product.getStockQuantity()).isEqualTo(10);
    }
}
