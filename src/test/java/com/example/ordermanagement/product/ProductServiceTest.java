package com.example.ordermanagement.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.common.ResourceNotFoundException;
import com.example.ordermanagement.order.OrderItemRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    @Mock private ProductRepository repository;
    @Mock private OrderItemRepository orderItemRepository;
    @InjectMocks private ProductService service;

    @Test
    void createsProductAndReturnsDto() {
        when(repository.save(any())).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            ReflectionTestUtils.setField(product, "id", 1L);
            return product;
        });
        ProductResponse result = service.create(request());
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("Keyboard");
        assertThat(result.price()).isEqualByComparingTo("29.99");
    }

    @Test
    void returnsStablePaginationMetadata() {
        Product product = product();
        PageRequest pageRequest = PageRequest.of(0, 2, Sort.by("id"));
        when(repository.findAll(pageRequest)).thenReturn(new PageImpl<>(List.of(product), pageRequest, 1));
        var result = service.findAll(0, 2);
        assertThat(result.content()).hasSize(1);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.last()).isTrue();
    }

    @Test
    void updatesLockedProduct() {
        Product product = product();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        ProductResponse result = service.update(1L, request());
        assertThat(result.stockQuantity()).isEqualTo(10);
        assertThat(result.name()).isEqualTo("Keyboard");
        verify(repository).findByIdForUpdate(1L);
    }

    @Test
    void deletesUnusedProduct() {
        Product product = product();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        when(orderItemRepository.existsByProductId(1L)).thenReturn(false);
        service.delete(1L);
        verify(repository).delete(product);
    }

    @Test
    void refusesDeletionOfProductWithOrderHistory() {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(product()));
        when(orderItemRepository.existsByProductId(1L)).thenReturn(true);
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(ConflictException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void missingProductFailsReadUpdateAndDelete() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(1L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(1L, request())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(ResourceNotFoundException.class);
    }

    private ProductRequest request() {
        return new ProductRequest(" Keyboard ", "Mechanical", new BigDecimal("29.99"), 10);
    }

    private Product product() {
        Product product = new Product("Old", null, new BigDecimal("1.00"), 1);
        ReflectionTestUtils.setField(product, "id", 1L);
        return product;
    }
}
