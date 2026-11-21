package com.example.ordermanagement.inventory;

import com.example.ordermanagement.support.TestSecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ordermanagement.common.BadRequestException;
import com.example.ordermanagement.order.CustomerOrder;
import com.example.ordermanagement.product.Product;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

class StockMovementTest {
    @ParameterizedTest
    @ValueSource(ints = {0, Integer.MAX_VALUE})
    void initialMovementIncludesZeroAndMaximumStock(int quantity) {
        StockMovement movement = StockMovement.initial(product(quantity));
        assertThat(movement.getQuantityDelta()).isEqualTo(quantity);
        assertThat(movement.getStockBefore()).isZero();
        assertThat(movement.getStockAfter()).isEqualTo(quantity);
        assertThat(movement.getType()).isEqualTo(StockMovementType.INITIAL_STOCK);
        assertThat(movement.getCreatedAt()).isNotNull();
    }

    @Test
    void snapshotsDoNotFollowLaterProductChanges() {
        Product product = product(10);
        CustomerOrder order = CustomerOrder.create(TestSecurity.user(1L));
        product.deductStock(3);
        StockMovement deduction = StockMovement.deduction(product, order, 3, 10);
        product.restoreStock(3);
        StockMovement cancellation = StockMovement.cancellation(product, order, 3, 7);
        assertThat(deduction.getStockAfter()).isEqualTo(7);
        assertThat(deduction.getQuantityDelta()).isEqualTo(-3);
        assertThat(cancellation.getStockAfter()).isEqualTo(10);
        assertThat(cancellation.getQuantityDelta()).isEqualTo(3);
        assertThat(deduction.getReferenceOrder()).isSameAs(order);
    }

    @Test
    void refusesInconsistentBalancesOrMissingOrderAndReason() {
        Product product = product(5);
        assertThatThrownBy(() -> StockMovement.restock(product, 2, 4, "Delivery"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockMovement.deduction(product, null, 1, 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockMovement.cancellation(product, CustomerOrder.create(TestSecurity.user(1L)), 0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockMovement.restock(product, 1, 4, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chronologicalSortingHasDeterministicIdTieBreaker() {
        assertThat(new StockMovementQuery(0, 20, null).pageable().getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        assertThat(new StockMovementQuery(1, 100, "createdAt,ASC").pageable().getSort())
                .isEqualTo(Sort.by("createdAt", "id"));
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,101", "2147483647,100"})
    void refusesInvalidHistoryPagination(int page, int size) {
        assertThatThrownBy(() -> new StockMovementQuery(page, size, null)).isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "createdAt,up", "createdAt,", "createdAt,asc,id", "quantityDelta,desc"})
    void refusesUnsupportedHistorySort(String sort) {
        assertThatThrownBy(() -> new StockMovementQuery(0, 20, sort)).isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, -3})
    void adjustmentKeepsSignedSnapshotAndReasonWithoutOrderReference(int delta) {
        Product product = product(10);
        product.adjustStock(delta);
        StockMovement movement = StockMovement.adjustment(product, delta, 10, "  Stock count  ");
        product.restoreStock(1);
        assertThat(movement.getType()).isEqualTo(StockMovementType.MANUAL_ADJUSTMENT);
        assertThat(movement.getQuantityDelta()).isEqualTo(delta);
        assertThat(movement.getStockBefore()).isEqualTo(10);
        assertThat(movement.getStockAfter()).isEqualTo(10 + delta);
        assertThat(movement.getReason()).isEqualTo("Stock count");
        assertThat(movement.getReferenceOrder()).isNull();
        assertThat(movement.getCreatedAt()).isNotNull();
    }

    @Test
    void adjustmentFactoryRejectsZeroInvalidReasonAndInconsistentBalances() {
        for (String reason : new String[]{null, "", " ", "x".repeat(501)}) {
            assertThatThrownBy(() -> StockMovement.adjustment(product(9), -1, 10, reason))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> StockMovement.adjustment(product(10), 0, 10, "Correction"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockMovement.adjustment(product(10), -1, 10, "Correction"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockMovement.adjustment(product(0), Integer.MIN_VALUE, Integer.MAX_VALUE, "Correction"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockMovement.adjustment(null, 1, 0, "Correction"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Product product(int stock) {
        return new Product("Product", null, new BigDecimal("1.00"), stock);
    }
}
