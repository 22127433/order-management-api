package com.example.ordermanagement.web;

import com.example.ordermanagement.auth.UserRepository;
import com.example.ordermanagement.security.SecurityConfig;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermanagement.order.IdempotentOrderService;
import com.example.ordermanagement.order.OrderController;
import com.example.ordermanagement.order.OrderIdempotencyRepository;
import com.example.ordermanagement.order.OrderResponse;
import com.example.ordermanagement.order.OrderService;
import com.example.ordermanagement.order.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@Import({IdempotentOrderService.class, SecurityConfig.class})
@WithMockUser(username = "1", roles = "ADMIN")
class OrderIdempotencyControllerTest {
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository users;
    private static final String BODY = "{\"items\":[{\"productId\":1,\"quantity\":2}]}";
    @MockitoBean private com.example.ordermanagement.security.CurrentUser currentUser;
    @org.junit.jupiter.api.BeforeEach
    void identityFixture() {
        org.mockito.Mockito.lenient().when(currentUser.id()).thenReturn(1L);
    }
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private OrderService orders;
    @MockitoBean private OrderIdempotencyRepository repository;

    @Test
    void firstKeyedRequestReturns201AndReplayFlag() throws Exception {
        when(repository.claim(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString())).thenReturn(true);
        when(orders.create(any())).thenReturn(response());
        mvc.perform(post("/api/orders").header("Idempotency-Key", "checkout-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/orders/7"))
                .andExpect(header().string("Idempotency-Replayed", "false")).andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void retryReturns200LocationAndStoredResponse() throws Exception {
        when(repository.findCompleted(1L, "retry")).thenReturn(new OrderIdempotencyRepository.StoredResult(
                "v1|1:2;", mapper.writeValueAsString(response())));
        mvc.perform(post("/api/orders").header("Idempotency-Key", "retry")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Location", "/api/orders/7"))
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("CREATED"));
        verifyNoInteractions(orders);
    }

    @Test
    void changedPayloadReturns409WithStandardError() throws Exception {
        when(repository.findCompleted(1L, "conflict")).thenReturn(new OrderIdempotencyRepository.StoredResult("v1|1:1;", "{}"));
        mvc.perform(post("/api/orders").header("Idempotency-Key", "conflict")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.path").value("/api/orders"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key was already used with a different order payload"));
        verifyNoInteractions(orders);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "has space", "a,b", "a/b"})
    void invalidHeaderReturns400(String key) throws Exception {
        mvc.perform(post("/api/orders").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository, orders);
    }

    @Test
    void multipleKeyHeadersAreRejected() throws Exception {
        mvc.perform(post("/api/orders").header("Idempotency-Key", "first", "second")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repository, orders);
    }

    @Test
    void beanValidationStillRunsBeforeClaimingKey() throws Exception {
        mvc.perform(post("/api/orders").header("Idempotency-Key", "validation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":1,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("items[0].quantity"));
        verifyNoInteractions(repository, orders);
    }

    @Test
    void lockTimeoutReturnsRetryable409() throws Exception {
        when(repository.claim(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString())).thenThrow(new CannotAcquireLockException("Busy"));
        mvc.perform(post("/api/orders").header("Idempotency-Key", "busy")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Resource is busy; retry the request"));
        verifyNoInteractions(orders);
    }

    @Test
    void missingHeaderKeepsExisting201Behavior() throws Exception {
        when(orders.create(any())).thenReturn(response());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotency-Replayed"));
        verifyNoInteractions(repository);
    }

    private OrderResponse response() {
        return new OrderResponse(7L, OrderStatus.CREATED, new BigDecimal("2.00"),
                Instant.parse("2026-11-17T01:02:03Z"), List.of());
    }
}
