package com.example.ordermanagement.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ordermanagement.common.BadRequestException;
import com.example.ordermanagement.common.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import com.example.ordermanagement.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotentOrderServiceTest {
    @Mock private OrderService orders;
    @Mock private CurrentUser currentUser;
    @Mock private OrderIdempotencyRepository repository;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private IdempotentOrderService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(currentUser.id()).thenReturn(1L);
        service = new IdempotentOrderService(orders, repository, mapper, currentUser);
    }

    @Test
    void claimsThenCreatesAndStoresOriginalResponse() throws Exception {
        when(repository.claim(1L, "checkout-1", "v1|1:2;")).thenReturn(true);
        when(orders.create(request())).thenReturn(response());
        var result = service.create("checkout-1", request());
        assertThat(result).isEqualTo(new OrderCreationResult(response(), false));
        var sequence = org.mockito.Mockito.inOrder(repository, orders);
        sequence.verify(repository).claim(1L, "checkout-1", "v1|1:2;");
        sequence.verify(orders).create(request());
        sequence.verify(repository).complete(1L, "checkout-1", 7L, mapper.writeValueAsString(response()));
    }

    @Test
    void replaysStoredSnapshotWithoutCreatingOrReadingCurrentOrder() throws Exception {
        when(repository.findCompleted(1L, "retry")).thenReturn(new OrderIdempotencyRepository.StoredResult(
                "v1|1:2;", mapper.writeValueAsString(response())));
        assertThat(service.create("retry", request())).isEqualTo(new OrderCreationResult(response(), true));
        verifyNoInteractions(orders);
        verify(repository, never()).complete(org.mockito.ArgumentMatchers.anyLong(), anyString(), any(), anyString());
    }

    @Test
    void differentPayloadConflictsWithoutCallingOrderService() {
        when(repository.findCompleted(1L, "retry")).thenReturn(new OrderIdempotencyRepository.StoredResult("v1|1:1;", "{}"));
        assertThatThrownBy(() -> service.create("retry", request())).isInstanceOf(ConflictException.class);
        verifyNoInteractions(orders);
    }

    @Test
    void itemOrderDoesNotChangeCanonicalPayload() throws Exception {
        var request = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(9L, 1), new CreateOrderRequest.Item(1L, 2)));
        when(repository.findCompleted(1L, "sorted")).thenReturn(new OrderIdempotencyRepository.StoredResult(
                "v1|1:2;9:1;", mapper.writeValueAsString(response())));
        assertThat(service.create("sorted", request).replayed()).isTrue();
        verify(repository).claim(1L, "sorted", "v1|1:2;9:1;");
        verifyNoInteractions(orders);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " key", "key ", "a,b", "a/b", "é", "a\tb"})
    void rejectsInvalidKeyBeforeDatabaseAccess(String key) {
        assertThatThrownBy(() -> service.create(key, request())).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository, orders);
    }

    @Test
    void rejectsKeyLongerThan128Characters() {
        assertThatThrownBy(() -> service.create("a".repeat(129), request())).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository, orders);
    }

    @Test
    void accepts128CharacterKeyWithSupportedPunctuation() {
        String key = "A0._:-" + "x".repeat(122);
        when(repository.claim(eq(1L), eq(key), anyString())).thenReturn(true);
        when(orders.create(any())).thenReturn(response());
        assertThat(service.create(key, request()).replayed()).isFalse();
    }

    @Test
    void rejectsDuplicateItemsBeforeClaimingKey() {
        var duplicate = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(1L, 1), new CreateOrderRequest.Item(1L, 2)));
        assertThatThrownBy(() -> service.create("bad", duplicate)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository, orders);
    }

    @Test
    void propagatesOrderFailureWithoutCompletingReservation() {
        when(repository.claim(1L, "failure", "v1|1:2;")).thenReturn(true);
        when(orders.create(any())).thenThrow(new ConflictException("Insufficient stock"));
        assertThatThrownBy(() -> service.create("failure", request())).isInstanceOf(ConflictException.class);
        verify(repository, never()).complete(org.mockito.ArgumentMatchers.anyLong(), anyString(), any(), anyString());
        // Repository mocks cannot demonstrate rollback; PostgreSQL integration tests do that.
    }

    @Test
    void serializationFailureBecomesRuntimeFailureForRollback() throws Exception {
        ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
        when(repository.claim(1L, "failure", "v1|1:2;")).thenReturn(true);
        when(orders.create(any())).thenReturn(response());
        when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("Forced encoding failure") { });
        var failing = new IdempotentOrderService(orders, repository, broken, currentUser);
        assertThatThrownBy(() -> failing.create("failure", request())).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).complete(org.mockito.ArgumentMatchers.anyLong(), anyString(), any(), anyString());
    }

    private CreateOrderRequest request() {
        return new CreateOrderRequest(List.of(new CreateOrderRequest.Item(1L, 2)));
    }

    private OrderResponse response() {
        return new OrderResponse(7L, OrderStatus.CREATED, new BigDecimal("2.00"),
                Instant.parse("2026-11-17T01:02:03Z"), List.of());
    }
}
