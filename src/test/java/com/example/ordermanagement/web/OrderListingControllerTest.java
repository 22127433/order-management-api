package com.example.ordermanagement.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermanagement.auth.UserRepository;
import com.example.ordermanagement.common.PageResponse;
import com.example.ordermanagement.order.IdempotentOrderService;
import com.example.ordermanagement.order.OrderController;
import com.example.ordermanagement.order.OrderListQuery;
import com.example.ordermanagement.order.OrderService;
import com.example.ordermanagement.order.OrderStatus;
import com.example.ordermanagement.order.OrderSummaryResponse;
import com.example.ordermanagement.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderListingControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private OrderService orders;
    @MockitoBean private IdempotentOrderService idempotency;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private UserRepository users;

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "ADMIN"})
    void bothRolesCanListWithDefaultsAndSummaryDto(String role) throws Exception {
        var summary = new OrderSummaryResponse(12L, OrderStatus.CREATED, new BigDecimal("19.99"),
                Instant.parse("2026-11-21T10:00:00Z"));
        when(orders.findAll(new OrderListQuery(null, null, null, 0, 20)))
                .thenReturn(new PageResponse<>(List.of(summary), 0, 20, 1, 1, true));
        mvc.perform(get("/api/orders").with(user("1").roles(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(12))
                .andExpect(jsonPath("$.content[0].status").value("CREATED"))
                .andExpect(jsonPath("$.content[0].totalAmount").value(19.99))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-11-21T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].items").doesNotExist())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void bindsStatusOffsetTimestampsAndPageParameters() throws Exception {
        var search = new OrderListQuery(OrderStatus.SHIPPED, Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-21T00:00:00Z"), 1, 5);
        when(orders.findAll(search)).thenReturn(new PageResponse<>(List.of(), 1, 5, 0, 0, true));
        mvc.perform(get("/api/orders").with(user("1").roles("CUSTOMER"))
                        .param("status", "SHIPPED").param("from", "2026-11-01T07:00:00+07:00")
                        .param("to", "2026-11-21T00:00:00Z").param("page", "1").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(5));
        verify(orders).findAll(search);
    }

    @ParameterizedTest
    @CsvSource({"status,UNKNOWN", "status,created", "from,not-a-date", "to,2026-11-21",
            "from,2026-11-21T10:00:00", "page,-1", "size,0", "size,101", "page,abc", "size,1.5",
            "page,2147483648"})
    void malformedFiltersReturn400BeforeCallingService(String name, String value) throws Exception {
        mvc.perform(get("/api/orders").with(user("1").roles("CUSTOMER")).param(name, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/orders"));
        verifyNoInteractions(orders, idempotency);
    }

    @Test
    void reversedRangeAndOverflowOffsetReturn400() throws Exception {
        mvc.perform(get("/api/orders").with(user("1").roles("CUSTOMER"))
                        .param("from", "2026-11-22T00:00:00Z").param("to", "2026-11-21T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").with(user("1").roles("CUSTOMER"))
                        .param("page", "2147483647").param("size", "100"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orders, idempotency);
    }

    @Test
    void anonymousListingReturns401BeforeValidationOrService() throws Exception {
        mvc.perform(get("/api/orders").param("status", "INVALID"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(orders, idempotency);
    }

    @Test
    void listingPermissionDoesNotOpenAdministrativeOrderActions() throws Exception {
        for (String action : List.of("confirm", "ship", "deliver", "cancel")) {
            mvc.perform(post("/api/orders/1/" + action).with(user("1").roles("CUSTOMER")))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/orders").with(user("1").roles("AUDITOR")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orders, idempotency);
    }

    @Test
    void clientOwnerAndSortParametersDoNotBecomeQueryInputs() throws Exception {
        when(orders.findAll(any())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));
        mvc.perform(get("/api/orders").with(user("1").roles("CUSTOMER"))
                        .param("userId", "999").param("sort", "id,asc"))
                .andExpect(status().isOk());
        verify(orders).findAll(new OrderListQuery(null, null, null, 0, 20));
    }
}
