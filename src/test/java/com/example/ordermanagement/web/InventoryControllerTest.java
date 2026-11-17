package com.example.ordermanagement.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.common.PageResponse;
import com.example.ordermanagement.common.ResourceNotFoundException;
import com.example.ordermanagement.inventory.StockMovementQuery;
import com.example.ordermanagement.inventory.StockMovementResponse;
import com.example.ordermanagement.inventory.StockMovementType;
import com.example.ordermanagement.product.ProductController;
import com.example.ordermanagement.product.ProductResponse;
import com.example.ordermanagement.product.ProductService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
class InventoryControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ProductService service;

    @Test
    void restockReturnsUpdatedProductWith200() throws Exception {
        when(service.restock(eq(1L), any()))
                .thenReturn(new ProductResponse(1L, "Product", null, BigDecimal.ONE, 15));
        mvc.perform(post("/api/products/1/restock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5,\"reason\":\"Supplier delivery\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQuantity").value(15));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":0,\"reason\":\"Delivery\"}",
        "{\"quantity\":-1,\"reason\":\"Delivery\"}", "{\"quantity\":1.5,\"reason\":\"Delivery\"}",
        "{\"quantity\":2147483648,\"reason\":\"Delivery\"}", "{\"quantity\":1}",
        "{\"quantity\":1,\"reason\":\" \"}", "{\"quantity\":1,\"reason\":null}",
        "{\"quantity\":1,\"reason\":\"Delivery\",\"extra\":true}"})
    void invalidRestockBodyReturns400BeforeService(String body) throws Exception {
        mvc.perform(post("/api/products/1/restock").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void overlongReasonReturns400() throws Exception {
        mvc.perform(post("/api/products/1/restock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void missingProductAndOverflowHaveExistingErrorShapes() throws Exception {
        when(service.restock(eq(1L), any())).thenThrow(new ResourceNotFoundException("Product", 1L));
        when(service.restock(eq(2L), any())).thenThrow(new ConflictException("Stock limit exceeded"));
        mvc.perform(post("/api/products/1/restock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"reason\":\"Delivery\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mvc.perform(post("/api/products/2/restock").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"reason\":\"Delivery\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void historyBindsPaginationAndReturnsMovementDtos() throws Exception {
        var movement = new StockMovementResponse(4L, 1L, StockMovementType.MANUAL_RESTOCK, 5,
                10, 15, null, "Delivery", Instant.parse("2026-11-17T10:00:00Z"));
        when(service.stockMovements(1L, new StockMovementQuery(1, 2, "createdAt,asc")))
                .thenReturn(new PageResponse<>(List.of(movement), 1, 2, 3, 2, true));
        mvc.perform(get("/api/products/1/stock-movements?page=1&size=2&sort=createdAt,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].quantityDelta").value(5))
                .andExpect(jsonPath("$.content[0].stockBefore").value(10))
                .andExpect(jsonPath("$.content[0].type").value("MANUAL_RESTOCK"))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void historyDefaultsToLatestFirstAndMissingProductIs404() throws Exception {
        when(service.stockMovements(1L, new StockMovementQuery(0, 20, "createdAt,desc")))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));
        when(service.stockMovements(eq(2L), any())).thenThrow(new ResourceNotFoundException("Product", 2L));
        mvc.perform(get("/api/products/1/stock-movements")).andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
        mvc.perform(get("/api/products/2/stock-movements")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/products/0/stock-movements", "/api/products/abc/stock-movements",
        "/api/products/1/stock-movements?page=-1", "/api/products/1/stock-movements?size=101",
        "/api/products/1/stock-movements?sort=id", "/api/products/1/stock-movements?sort=createdAt,up",
        "/api/products/1/stock-movements?page=2147483647&size=100"})
    void invalidHistoryQueryReturns400(String uri) throws Exception {
        mvc.perform(get(uri)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void invalidRestockIdReturns400(String id) throws Exception {
        mvc.perform(post("/api/products/{id}/restock", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"reason\":\"Delivery\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
