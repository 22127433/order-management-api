package com.example.ordermanagement.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.common.BadRequestException;
import com.example.ordermanagement.inventory.RestockRequest;
import com.example.ordermanagement.inventory.StockMovement;
import com.example.ordermanagement.inventory.StockMovementQuery;
import com.example.ordermanagement.inventory.StockMovementRepository;
import com.example.ordermanagement.inventory.StockMovementType;
import com.example.ordermanagement.common.ResourceNotFoundException;
import com.example.ordermanagement.order.OrderItemRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    @Mock private ProductRepository repository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private StockMovementRepository stockMovementRepository;
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
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(movement.capture());
        assertThat(movement.getValue().getType()).isEqualTo(StockMovementType.INITIAL_STOCK);
        assertThat(movement.getValue().getStockBefore()).isZero();
        assertThat(movement.getValue().getStockAfter()).isEqualTo(10);
    }

    @Test
    void returnsStablePaginationMetadata() {
        Product product = product();
        PageRequest pageRequest = PageRequest.of(0, 2, Sort.by("id"));
        when(repository.findAll(ArgumentMatchers.<Specification<Product>>any(), eq(pageRequest)))
                .thenReturn(new PageImpl<>(List.of(product), pageRequest, 1));
        var result = service.findAll(0, 2);
        assertThat(result.content()).hasSize(1);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.last()).isTrue();
        verify(repository).findAll(ArgumentMatchers.<Specification<Product>>any(), eq(pageRequest));
        verify(repository, never()).findAll();
    }

    @Test
    void delegatesSearchAndSortedPaginationToRepository() {
        ProductSearchQuery search = new ProductSearchQuery("old", new BigDecimal("0.50"),
                new BigDecimal("2.00"), true, 1, 2, "price,desc");
        PageRequest pageable = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "price").and(Sort.by("id")));
        when(repository.findAll(ArgumentMatchers.<Specification<Product>>any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(product()), pageable, 3));

        var result = service.findAll(search);

        assertThat(result.content()).extracting(ProductResponse::name).containsExactly("Old");
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.last()).isTrue();
        verify(repository).findAll(ArgumentMatchers.<Specification<Product>>any(), eq(pageable));
        verify(repository, never()).findAll();
    }

    @Test
    void updatesLockedProduct() {
        Product product = product();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        ProductResponse result = service.update(1L, new UpdateProductRequest("Keyboard", "Mechanical", new BigDecimal("29.99")));
        assertThat(result.stockQuantity()).isEqualTo(1);
        assertThat(result.name()).isEqualTo("Keyboard");
        verify(repository).findByIdForUpdate(1L);
        verifyNoInteractions(stockMovementRepository);
    }

    @Test
    void metadataUpdatePreservesStockChangedByOrderAndInactiveFlag() {
        Product product = product();
        product.deductStock(1);
        product.changeAvailability(false);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        ProductResponse response = service.update(1L, new UpdateProductRequest("Updated", null, new BigDecimal("2.00")));
        assertThat(response.stockQuantity()).isZero();
        assertThat(response.active()).isFalse();
        assertThat(response.name()).isEqualTo("Updated");
        verifyNoInteractions(stockMovementRepository);
    }

    @Test
    void restocksLockedProductAndWritesMatchingMovement() {
        Product product = product();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        assertThat(service.restock(1L, new RestockRequest(5, "  Supplier delivery  ")).stockQuantity()).isEqualTo(6);
        ArgumentCaptor<StockMovement> movement = ArgumentCaptor.forClass(StockMovement.class);
        verify(stockMovementRepository).save(movement.capture());
        assertThat(movement.getValue().getType()).isEqualTo(StockMovementType.MANUAL_RESTOCK);
        assertThat(movement.getValue().getQuantityDelta()).isEqualTo(5);
        assertThat(movement.getValue().getStockBefore()).isEqualTo(1);
        assertThat(movement.getValue().getStockAfter()).isEqualTo(6);
        assertThat(movement.getValue().getReason()).isEqualTo("Supplier delivery");
        assertThat(movement.getValue().getReferenceOrder()).isNull();
        verify(repository).findByIdForUpdate(1L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 0})
    void rejectsInvalidRestockQuantityBeforeLock(Integer quantity) {
        assertThatThrownBy(() -> service.restock(1L, new RestockRequest(quantity, "Delivery")))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository, stockMovementRepository);
    }

    @Test
    void rejectsInvalidRestockReasonAndNullRequest() {
        for (String reason : new String[]{null, "", " ", "x".repeat(501)}) {
            assertThatThrownBy(() -> service.restock(1L, new RestockRequest(1, reason)))
                    .isInstanceOf(BadRequestException.class);
        }
        assertThatThrownBy(() -> service.restock(1L, null)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository, stockMovementRepository);
    }

    @Test
    void refusesRestockOverflowWithoutWritingHistory() {
        Product product = product();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        assertThatThrownBy(() -> service.restock(1L, new RestockRequest(Integer.MAX_VALUE, "Delivery")))
                .isInstanceOf(ConflictException.class);
        assertThat(product.getStockQuantity()).isEqualTo(1);
        verifyNoInteractions(stockMovementRepository);
    }

    @Test
    void returnsHistoryAsPagedDtoAndChecksProductExists() {
        Product product = product();
        var query = new StockMovementQuery(0, 2, null);
        var movement = StockMovement.initial(product);
        when(repository.existsById(1L)).thenReturn(true);
        when(stockMovementRepository.findByProductId(1L, query.pageable()))
                .thenReturn(new PageImpl<>(List.of(movement), query.pageable(), 1));
        var result = service.stockMovements(1L, query);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().productId()).isEqualTo(1L);
        assertThat(result.content().getFirst().type()).isEqualTo(StockMovementType.INITIAL_STOCK);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThatThrownBy(() -> service.stockMovements(2L, query)).isInstanceOf(ResourceNotFoundException.class);
        verify(stockMovementRepository, never()).findByProductId(eq(2L), any());
    }

    @Test
    void refusesDeletionOfProductWithMovementHistory() {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(product()));
        when(stockMovementRepository.existsByProductId(1L)).thenReturn(true);
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(ConflictException.class);
        verify(repository, never()).delete(any(Product.class));
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
        verify(repository, never()).delete(any(Product.class));
    }

    @Test
    void missingProductFailsReadUpdateAndDelete() {
        when(repository.findByIdAndActiveTrue(1L)).thenReturn(Optional.empty());
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(1L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(1L, new UpdateProductRequest("Updated", null, BigDecimal.ONE)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.restock(1L, new RestockRequest(1, "Delivery")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void availabilityChangesLockProductWithoutChangingStockOrWritingMovements() {
        Product product = product();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        assertThat(product.isActive()).isTrue();
        for (boolean active : List.of(false, false, true, true)) {
            ProductResponse response = service.changeAvailability(1L, new ProductAvailabilityRequest(active));
            assertThat(response.active()).isEqualTo(active);
            assertThat(response.stockQuantity()).isEqualTo(1);
            assertThat(response.name()).isEqualTo("Old");
        }
        verify(repository, org.mockito.Mockito.times(4)).findByIdForUpdate(1L);
        verifyNoInteractions(stockMovementRepository, orderItemRepository);
        verify(repository, never()).save(any());
    }

    @Test
    void availabilityRequiresValueBeforeAcquiringLock() {
        assertThatThrownBy(() -> service.changeAvailability(1L, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.changeAvailability(1L, new ProductAvailabilityRequest(null)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository, stockMovementRepository);
    }

    @Test
    void availabilityMissingProductReturnsNotFoundWithoutHistoryWrite() {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changeAvailability(1L, new ProductAvailabilityRequest(false)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(stockMovementRepository);
    }

    @Test
    void publicReadCannotFallBackToInactiveProductLookup() {
        when(repository.findByIdAndActiveTrue(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(1L)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).findById(any());
    }

    @Test
    void adminCatalogReusesDatabasePaginationAndIncludesAvailabilityInDto() {
        Product product = product();
        product.changeAvailability(false);
        var search = new ProductSearchQuery(null, null, null, null, 0, 20, null);
        when(repository.findAll(ArgumentMatchers.<Specification<Product>>any(), eq(search.pageable())))
                .thenReturn(new PageImpl<>(List.of(product), search.pageable(), 1));
        assertThat(service.findAllForAdmin(search, false).content()).extracting(ProductResponse::active).containsExactly(false);
        verify(repository, never()).findAll();
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
