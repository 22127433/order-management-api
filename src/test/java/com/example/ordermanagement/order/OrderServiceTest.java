package com.example.ordermanagement.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ordermanagement.common.BadRequestException;
import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.common.ResourceNotFoundException;
import com.example.ordermanagement.product.Product;
import com.example.ordermanagement.product.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @InjectMocks private OrderService service;

    @Test
    void createsMultipleItemsUsingExactMoneyAndOrderedLocks() {
        Product first = product(1L, "0.10", 10);
        Product second = product(2L, "19.99", 5);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        when(productRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));
        when(orderRepository.save(any())).thenAnswer(invocation -> {
            CustomerOrder order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 10L);
            return order;
        });

        OrderResponse response = service.create(request(item(2L, 2), item(1L, 3)));

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.totalAmount()).isEqualByComparingTo("40.28");
        assertThat(response.items()).extracting(OrderResponse.Item::productId).containsExactly(1L, 2L);
        assertThat(response.items().getFirst().subtotal()).isEqualByComparingTo("0.30");
        assertThat(first.getStockQuantity()).isEqualTo(7);
        assertThat(second.getStockQuantity()).isEqualTo(3);
        var lockOrder = inOrder(productRepository);
        lockOrder.verify(productRepository).findByIdForUpdate(1L);
        lockOrder.verify(productRepository).findByIdForUpdate(2L);
    }

    @Test
    void insufficientStockDoesNotChangeOtherProducts() {
        Product first = product(1L, "10.00", 10);
        Product second = product(2L, "20.00", 1);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        when(productRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));

        assertThatThrownBy(() -> service.create(request(item(1L, 2), item(2L, 2))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Insufficient stock");

        assertThat(first.getStockQuantity()).isEqualTo(10);
        assertThat(second.getStockQuantity()).isEqualTo(1);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void missingProductDoesNotChangeStock() {
        Product first = product(1L, "10.00", 10);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        when(productRepository.findByIdForUpdate(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(item(1L, 2), item(2L, 2))))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(first.getStockQuantity()).isEqualTo(10);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void rejectsDuplicateProductsBeforeAccessingDatabase() {
        assertThatThrownBy(() -> service.create(request(item(1L, 2), item(1L, 3))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Duplicate productId");
        verifyNoInteractions(productRepository, orderRepository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0, 1_000_001, Integer.MAX_VALUE})
    void rejectsInvalidQuantityBeforeAccessingDatabase(Integer quantity) {
        var invalid = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(1L, quantity)));

        assertThatThrownBy(() -> service.create(invalid))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("quantity");
        verifyNoInteractions(productRepository, orderRepository);
    }

    @Test
    void rejectsEmptyOrderBeforeAccessingDatabase() {
        assertThatThrownBy(() -> service.create(new CreateOrderRequest(List.of())))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(productRepository, orderRepository);
    }

    @Test
    void cancelsAndRestoresEveryProductOnceInOrder() {
        Product first = product(1L, "12.50", 8);
        Product second = product(2L, "2.00", 7);
        CustomerOrder order = CustomerOrder.create();
        order.addItem(second, 3);
        order.addItem(first, 2);
        ReflectionTestUtils.setField(order, "id", 1L);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        when(productRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));

        OrderResponse result = service.cancel(1L);

        assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(result.totalAmount()).isEqualByComparingTo("31.00");
        assertThat(first.getStockQuantity()).isEqualTo(10);
        assertThat(second.getStockQuantity()).isEqualTo(10);
        var locks = inOrder(orderRepository, productRepository);
        locks.verify(orderRepository).findByIdForUpdate(1L);
        locks.verify(productRepository).findByIdForUpdate(1L);
        locks.verify(productRepository).findByIdForUpdate(2L);

        assertThatThrownBy(() -> service.cancel(1L)).isInstanceOf(ConflictException.class);
        assertThat(first.getStockQuantity()).isEqualTo(10);
        assertThat(second.getStockQuantity()).isEqualTo(10);
    }

    @Test
    void alreadyCancelledOrderDoesNotLockOrRestoreProducts() {
        CustomerOrder order = CustomerOrder.create();
        order.cancel();
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(1L)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(productRepository);
    }

    @Test
    void cancelRejectsStockOverflowBeforeRestoringAnyProduct() {
        Product first = product(1L, "1.00", 5);
        Product second = product(2L, "1.00", Integer.MAX_VALUE);
        CustomerOrder order = CustomerOrder.create();
        order.addItem(first, 2);
        order.addItem(second, 1);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        when(productRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));

        assertThatThrownBy(() -> service.cancel(1L)).isInstanceOf(ConflictException.class);
        assertThat(first.getStockQuantity()).isEqualTo(5);
        assertThat(second.getStockQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void missingOrderReturnsNotFoundForReadAndCancel() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(1L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(1L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void orderRetainsPriceSnapshotAfterProductPriceChanges() {
        Product product = product(1L, "9.99", 10);
        CustomerOrder order = CustomerOrder.create();
        order.addItem(product, 3);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        product.update("Updated", null, new BigDecimal("100.00"), 7);

        OrderResponse response = service.getById(1L);

        assertThat(response.totalAmount()).isEqualByComparingTo("29.97");
        assertThat(response.items().getFirst().unitPrice()).isEqualByComparingTo("9.99");
    }

    private Product product(Long id, String price, int stock) {
        Product product = new Product("Product " + id, null, new BigDecimal(price), stock);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private CreateOrderRequest.Item item(Long id, int quantity) {
        return new CreateOrderRequest.Item(id, quantity);
    }

    private CreateOrderRequest request(CreateOrderRequest.Item... items) {
        return new CreateOrderRequest(List.of(items));
    }
}
