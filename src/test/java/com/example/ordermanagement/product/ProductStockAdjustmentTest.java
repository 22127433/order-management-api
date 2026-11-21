package com.example.ordermanagement.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ordermanagement.common.ConflictException;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductStockAdjustmentTest {
    @ParameterizedTest
    @CsvSource({"10,5,15", "10,-3,7", "10,-10,0", "0,2147483647,2147483647",
            "2147483647,-2147483647,0", "2147483646,1,2147483647"})
    void appliesSignedDeltaIncludingExactStorageBounds(int before, int delta, int after) {
        Product product = new Product("Product", null, BigDecimal.ONE, before);
        product.changeAvailability(false);
        product.adjustStock(delta);
        assertThat(product.getStockQuantity()).isEqualTo(after);
        assertThat(product.isActive()).isFalse();
        assertThat(product.getPrice()).isEqualByComparingTo("1.00");
    }

    @ParameterizedTest
    @CsvSource({"10,-11", "0,-1", "2147483647,1", "10,2147483647", "2147483647,-2147483648"})
    void rejectsOutOfRangeResultBeforeMutatingState(int before, int delta) {
        Product product = new Product("Product", null, BigDecimal.ONE, before);
        assertThatThrownBy(() -> product.adjustStock(delta)).isInstanceOf(ConflictException.class);
        assertThat(product.getStockQuantity()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"0", "10", "2147483647"})
    void rejectsZeroWithoutMutatingStock(int before) {
        Product product = new Product("Product", null, BigDecimal.ONE, before);
        assertThatThrownBy(() -> product.adjustStock(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getStockQuantity()).isEqualTo(before);
    }
}
