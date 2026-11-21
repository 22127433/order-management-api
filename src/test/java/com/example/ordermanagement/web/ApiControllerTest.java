package com.example.ordermanagement.web;

import com.example.ordermanagement.auth.UserRepository;
import com.example.ordermanagement.security.SecurityConfig;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.context.annotation.Import;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.common.PageResponse;
import com.example.ordermanagement.common.ResourceNotFoundException;
import com.example.ordermanagement.order.OrderController;
import com.example.ordermanagement.order.IdempotentOrderService;
import com.example.ordermanagement.order.OrderResponse;
import com.example.ordermanagement.order.OrderService;
import com.example.ordermanagement.order.OrderStatus;
import com.example.ordermanagement.product.ProductController;
import com.example.ordermanagement.product.ProductResponse;
import com.example.ordermanagement.product.ProductSearchQuery;
import com.example.ordermanagement.product.ProductService;
import com.example.ordermanagement.product.UpdateProductRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({ProductController.class, OrderController.class})
@Import(SecurityConfig.class)
@WithMockUser(username = "1", roles = "ADMIN")
class ApiControllerTest {
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository users;
    private static final String PRODUCT = """
            {"name":"Keyboard","description":"Mechanical","price":29.99,"stockQuantity":10}
            """;
    private static final String METADATA = """
            {"name":"Keyboard","description":"Mechanical","price":29.99}
            """;
    @Autowired private MockMvc mvc;
    @MockitoBean private ProductService productService;
    @MockitoBean private OrderService orderService;
    @MockitoBean private IdempotentOrderService idempotentOrders;

    @Test
    void createProductReturns201AndLocation() throws Exception {
        when(productService.create(any())).thenReturn(product());
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(PRODUCT))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/products/1"))
                .andExpect(jsonPath("$.id").value(1)).andExpect(jsonPath("$.price").value(29.99));
    }

    @Test
    void readUpdateAndListReturn200WithDto() throws Exception {
        when(productService.getById(1L)).thenReturn(product());
        when(productService.update(any(), any())).thenReturn(product());
        when(productService.findAll(new ProductSearchQuery(null, null, null, null, 0, 20, "id,asc")))
                .thenReturn(new PageResponse<>(List.of(product()), 0, 20, 1, 1, true));
        mvc.perform(get("/api/products/1")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Keyboard"));
        mvc.perform(put("/api/products/1").contentType(MediaType.APPLICATION_JSON).content(METADATA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQuantity").value(10));
        mvc.perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1)).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void metadataPutDoesNotRequireStockAndBindsDedicatedDto() throws Exception {
        when(productService.update(1L, new UpdateProductRequest("Keyboard", null, new BigDecimal("29.99")))).thenReturn(product());
        mvc.perform(put("/api/products/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Keyboard\",\"price\":29.99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQuantity").value(10));
        verify(productService).update(1L, new UpdateProductRequest("Keyboard", null, new BigDecimal("29.99")));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10})
    void putStockFieldIsUnknownEvenWhenItMatchesCurrentStock(int stock) throws Exception {
        mvc.perform(put("/api/products/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Keyboard\",\"price\":29.99,\"stockQuantity\":" + stock + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\" \",\"price\":1}", "{\"name\":\"X\",\"price\":null}",
            "{\"name\":\"X\",\"price\":0}", "{\"name\":\"X\",\"price\":-1}", "{\"name\":\"X\",\"price\":1.001}",
            "{\"name\":\"X\",\"price\":1000000000000}", "{\"name\":\"X\",\"price\":1,\"active\":false}"})
    void invalidMetadataPutReturns400BeforeService(String body) throws Exception {
        mvc.perform(put("/api/products/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @Test
    void metadataPutRetainsNameAndDescriptionLengthLimits() throws Exception {
        for (String body : List.of("{\"name\":\"" + "x".repeat(121) + "\",\"price\":1}",
                "{\"name\":\"X\",\"description\":\"" + "x".repeat(2001) + "\",\"price\":1}")) {
            mvc.perform(put("/api/products/1").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(productService);
    }

    @Test
    void bindsSearchFiltersAndReturnsExistingPageShape() throws Exception {
        var search = new ProductSearchQuery("KEYboard", new BigDecimal("10.00"),
                new BigDecimal("40.00"), true, 1, 2, "price,desc");
        when(productService.findAll(search))
                .thenReturn(new PageResponse<>(List.of(product()), 1, 2, 3, 2, true));
        mvc.perform(get("/api/products").param("keyword", "  KEYboard  ")
                        .param("minPrice", "10.00").param("maxPrice", "40.00").param("inStock", "true")
                        .param("page", "1").param("size", "2").param("sort", "price,desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2));
        verify(productService).findAll(search);
    }

    @Test
    void bindsOutOfStockFilter() throws Exception {
        var search = new ProductSearchQuery(null, null, null, false, 0, 20, "id,asc");
        when(productService.findAll(search)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));
        mvc.perform(get("/api/products").param("inStock", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        verify(productService).findAll(search);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "minPrice=-0.01", "maxPrice=-1", "minPrice=10.01&maxPrice=10", "minPrice=abc",
        "maxPrice=NaN", "inStock=unknown", "page=abc", "size=1.5", "sort=description",
        "sort=name.length", "sort=price,up", "sort=price,", "sort=price,asc,id", "page=2147483647&size=100"
    })
    void invalidSearchQueriesReturn400BeforeServiceIsCalled(String query) throws Exception {
        mvc.perform(get("/api/products?" + query)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/products"));
        verifyNoInteractions(productService);
    }

    @Test
    void overlongKeywordReturns400() throws Exception {
        mvc.perform(get("/api/products").param("keyword", "x".repeat(121)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(productService);
    }

    @Test
    void deleteReturns204WithoutBody() throws Exception {
        doNothing().when(productService).delete(1L);
        mvc.perform(delete("/api/products/1")).andExpect(status().isNoContent());
    }

    @Test
    void createOrderReturns201AndLocation() throws Exception {
        when(orderService.create(any())).thenReturn(order(OrderStatus.CREATED));
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":2}]}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/orders/10"))
                .andExpect(jsonPath("$.totalAmount").value(59.98));
    }

    @Test
    void readAndCancelOrderReturn200() throws Exception {
        when(orderService.getById(10L)).thenReturn(order(OrderStatus.CREATED));
        when(orderService.cancel(10L)).thenReturn(order(OrderStatus.CANCELLED));
        mvc.perform(get("/api/orders/10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));
        mvc.perform(post("/api/orders/10/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @ParameterizedTest
    @CsvSource({"confirm,CONFIRMED", "ship,SHIPPED", "deliver,DELIVERED"})
    void lifecycleActionsReturn200WithOrderDto(String action, OrderStatus status) throws Exception {
        when(lifecycleCall(action)).thenReturn(order(status));
        mvc.perform(post("/api/orders/10/{action}", action)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10)).andExpect(jsonPath("$.status").value(status.name()))
                .andExpect(jsonPath("$.totalAmount").value(59.98))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "ship", "deliver"})
    void missingOrderForLifecycleActionReturns404(String action) throws Exception {
        when(lifecycleCall(action)).thenThrow(new ResourceNotFoundException("Order", 10L));
        mvc.perform(post("/api/orders/10/{action}", action)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "ship", "deliver"})
    void invalidLifecycleTransitionReturns409(String action) throws Exception {
        when(lifecycleCall(action)).thenThrow(new ConflictException("Transition is not allowed"));
        mvc.perform(post("/api/orders/10/{action}", action)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Transition is not allowed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "ship", "deliver"})
    void lifecycleLockConflictReturns409WithoutSqlDetails(String action) throws Exception {
        when(lifecycleCall(action)).thenThrow(new CannotAcquireLockException("internal SQL"));
        mvc.perform(post("/api/orders/10/{action}", action)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Resource is busy; retry the request"));
    }

    @ParameterizedTest
    @CsvSource({"confirm,0", "confirm,-1", "confirm,abc", "ship,0", "ship,-1", "ship,abc",
            "deliver,0", "deliver,-1", "deliver,abc"})
    void invalidLifecycleIdReturns400BeforeService(String action, String id) throws Exception {
        mvc.perform(post("/api/orders/{id}/{action}", id, action)).andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"name\":\" \",\"price\":1,\"stockQuantity\":0}",
        "{\"name\":\"X\",\"price\":-1,\"stockQuantity\":0}",
        "{\"name\":\"X\",\"price\":1.001,\"stockQuantity\":0}",
        "{\"name\":\"X\",\"price\":1000000000000,\"stockQuantity\":0}",
        "{\"name\":\"X\",\"price\":1,\"stockQuantity\":-1}",
        "{\"name\":\"X\",\"price\":1,\"stockQuantity\":1.5}",
        "{\"name\":\"X\",\"price\":1,\"stockQuantity\":0,\"unexpected\":true}",
        "{"
    })
    void invalidProductsReturn400(String body) throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/products"));
        verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"items\":[]}", "{\"items\":[null]}",
        "{\"items\":[{\"productId\":1,\"quantity\":0}]}",
        "{\"items\":[{\"productId\":1,\"quantity\":1000001}]}",
        "{\"items\":[{\"productId\":0,\"quantity\":1}]}",
        "{\"items\":[{\"productId\":1}]}",
        "{\"items\":[{\"productId\":1,\"quantity\":1.5}]}"
    })
    void invalidNestedOrderItemsReturn400(String body) throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(orderService);
    }

    @Test
    void beanValidationProvidesFieldErrors() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/products?page=-1", "/api/products?size=0", "/api/products?size=101",
            "/api/products/0", "/api/orders/-1", "/api/products/abc"})
    void invalidPaginationOrIdReturns400(String uri) throws Exception {
        mvc.perform(get(uri)).andExpect(status().isBadRequest());
        verifyNoInteractions(productService, orderService);
    }

    @Test
    void missingProductReturns404() throws Exception {
        when(productService.getById(1L)).thenThrow(new ResourceNotFoundException("Product", 1L));
        mvc.perform(get("/api/products/1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void businessConflictReturns409() throws Exception {
        when(orderService.cancel(10L)).thenThrow(new ConflictException("Order 10 is already cancelled"));
        mvc.perform(post("/api/orders/10/cancel")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Order 10 is already cancelled"));
    }

    @Test
    void lockTimeoutReturns409() throws Exception {
        when(orderService.cancel(10L)).thenThrow(new CannotAcquireLockException("internal SQL"));
        mvc.perform(post("/api/orders/10/cancel")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Resource is busy; retry the request"));
    }

    @Test
    void foreignKeyConflictReturns409WithoutSqlDetails() throws Exception {
        when(productService.update(any(), any())).thenThrow(new DataIntegrityViolationException("internal SQL"));
        mvc.perform(put("/api/products/1").contentType(MediaType.APPLICATION_JSON).content(METADATA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Operation conflicts with existing data"));
    }

    @Test
    void unexpectedFailureReturns500WithoutInternalDetails() throws Exception {
        when(productService.getById(1L)).thenThrow(new IllegalStateException("sensitive internals"));
        mvc.perform(get("/api/products/1")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    private OrderResponse lifecycleCall(String action) {
        return switch (action) {
            case "confirm" -> orderService.confirm(10L);
            case "ship" -> orderService.ship(10L);
            case "deliver" -> orderService.deliver(10L);
            default -> throw new IllegalArgumentException("Unsupported action in test: " + action);
        };
    }

    private ProductResponse product() {
        return new ProductResponse(1L, "Keyboard", "Mechanical", new BigDecimal("29.99"), 10);
    }

    private OrderResponse order(OrderStatus status) {
        return new OrderResponse(10L, status, new BigDecimal("59.98"), Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new OrderResponse.Item(1L, 1L, 2, new BigDecimal("29.99"), new BigDecimal("59.98"))));
    }
}
