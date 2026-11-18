package com.example.ordermanagement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.order.CreateOrderRequest;
import com.example.ordermanagement.order.OrderRepository;
import com.example.ordermanagement.order.OrderResponse;
import com.example.ordermanagement.order.OrderService;
import com.example.ordermanagement.order.OrderStatus;
import com.example.ordermanagement.product.ProductRequest;
import com.example.ordermanagement.product.ProductResponse;
import com.example.ordermanagement.product.ProductService;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderManagementIT extends com.example.ordermanagement.support.AuthenticatedIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ProductService productService;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE order_items, customer_orders, products RESTART IDENTITY CASCADE");
    }

    @Test
    void concurrentOrdersCannotOversellLastUnit() throws Exception {
        ProductResponse product = product("10.99", 1);
        List<Boolean> outcomes = overlapTransactions(() -> {
            orderService.create(request(product.id(), 1));
            return true;
        }, () -> {
            try {
                orderService.create(request(product.id(), 1));
                return true;
            } catch (ConflictException exception) {
                assertThat(exception).hasMessageContaining("Insufficient stock");
                return false;
            }
        }, "products");

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(productService.getById(product.id()).stockQuantity()).isZero();
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE movement_type='ORDER_DEDUCTION'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentCancellationRestoresStockOnlyOnce() throws Exception {
        ProductResponse product = product("5.00", 10);
        OrderResponse order = orderService.create(request(product.id(), 3));

        List<Boolean> outcomes = overlapTransactions(() -> {
            orderService.cancel(order.id());
            return true;
        }, () -> {
            try {
                orderService.cancel(order.id());
                return true;
            } catch (ConflictException exception) {
                assertThat(exception).hasMessageContaining("already cancelled");
                return false;
            }
        }, "customer_orders");

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(productService.getById(product.id()).stockQuantity()).isEqualTo(10);
        assertThat(orderService.getById(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE movement_type='ORDER_CANCELLATION'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void persistenceFailureRollsBackInventoryDeduction() {
        ProductResponse product = product("10.00", 10);
        // IDENTITY inserts write the order/items before Hibernate flushes the stock update.
        // Reject that UPDATE to prove those earlier INSERTs are rolled back too.
        rejectStockUpdates();
        try {
            assertThatThrownBy(() -> orderService.create(request(product.id(), 3)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            allowStockUpdates();
        }
        assertThat(productService.getById(product.id()).stockQuantity()).isEqualTo(10);
        assertThat(orderRepository.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isZero();
    }

    @Test
    void failedCancellationRollsBackOrderStatusAndEveryStockChange() {
        ProductResponse first = product("10.00", 10);
        ProductResponse second = product("2.00", 10);
        OrderResponse order = orderService.create(new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(first.id(), 2), new CreateOrderRequest.Item(second.id(), 3))));
        rejectStockUpdates();
        try {
            assertThatThrownBy(() -> orderService.cancel(order.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            allowStockUpdates();
        }

        assertThat(orderService.getById(order.id()).status()).isEqualTo(OrderStatus.CREATED);
        assertThat(productService.getById(first.id()).stockQuantity()).isEqualTo(8);
        assertThat(productService.getById(second.id()).stockQuantity()).isEqualTo(7);
        assertThat(orderService.cancel(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(productService.getById(first.id()).stockQuantity()).isEqualTo(10);
        assertThat(productService.getById(second.id()).stockQuantity()).isEqualTo(10);
    }

    @Test
    void insufficientStockOnSecondProductLeavesNoPartialOrder() {
        ProductResponse first = product("0.10", 10);
        ProductResponse second = product("19.99", 1);
        var request = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(first.id(), 3), new CreateOrderRequest.Item(second.id(), 2)));
        assertThatThrownBy(() -> orderService.create(request)).isInstanceOf(ConflictException.class);
        assertThat(productService.getById(first.id()).stockQuantity()).isEqualTo(10);
        assertThat(productService.getById(second.id()).stockQuantity()).isEqualTo(1);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void preservesPricesAndProtectsOrderHistoryAfterCancellation() {
        ProductResponse product = product("0.10", 10);
        OrderResponse created = orderService.create(request(product.id(), 3));
        productService.update(product.id(), new ProductRequest("New price", null, new BigDecimal("99.99"), 7));
        OrderResponse loaded = orderService.getById(created.id());
        assertThat(loaded.totalAmount()).isEqualByComparingTo("0.30");
        assertThat(loaded.items().getFirst().unitPrice()).isEqualByComparingTo("0.10");
        assertThat(loaded.items().getFirst().id()).isNotNull();

        orderService.cancel(created.id());
        assertThat(productService.getById(product.id()).stockQuantity()).isEqualTo(10);
        assertThatThrownBy(() -> productService.delete(product.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void duplicateProductsAndInsufficientStockHaveCorrectHttpStatuses() throws Exception {
        ProductResponse product = product("10.00", 1);
        String duplicate = "{\"items\":[{\"productId\":%d,\"quantity\":1},{\"productId\":%d,\"quantity\":1}]}"
                .formatted(product.id(), product.id());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isBadRequest());
        String unavailable = "{\"items\":[{\"productId\":%d,\"quantity\":2}]}".formatted(product.id());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(unavailable))
                .andExpect(status().isConflict());
        assertThat(productService.getById(product.id()).stockQuantity()).isEqualTo(1);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void concurrentOrdersWithReversedItemsFinishWithoutDeadlock() throws Exception {
        ProductResponse first = product("1.25", 10);
        ProductResponse second = product("2.50", 10);
        CreateOrderRequest forward = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(first.id(), 1), new CreateOrderRequest.Item(second.id(), 1)));
        CreateOrderRequest reverse = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(second.id(), 1), new CreateOrderRequest.Item(first.id(), 1)));
        List<Boolean> results = raceTasks(() -> {
            orderService.create(forward);
            return true;
        }, () -> {
            orderService.create(reverse);
            return true;
        });
        assertThat(results).containsExactly(true, true);
        assertThat(productService.getById(first.id()).stockQuantity()).isEqualTo(8);
        assertThat(productService.getById(second.id()).stockQuantity()).isEqualTo(8);
    }

    @Test
    void productCrudAndPaginationWorkThroughHttpAndPostgresql() throws Exception {
        String body = """
                {"name":"Keyboard","description":"USB","price":29.99,"stockQuantity":10}
                """;
        var result = mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andReturn();
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(get("/api/products/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keyboard"));
        mvc.perform(get("/api/products?page=0&size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.size").value(1));
        mvc.perform(put("/api/products/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Updated","description":null,"price":12.50,"stockQuantity":10}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(12.50));
        assertThat(productService.getById(id).stockQuantity()).isEqualTo(10);
        assertThat(productService.getById(id).description()).isNull();

        mvc.perform(delete("/api/products/{id}", id)).andExpect(status().isConflict());
        // Legacy products without order references or journal entries remain deletable.
        long legacyId = jdbc.queryForObject("INSERT INTO products(name,price,stock_quantity) VALUES ('Legacy',1,0) RETURNING id", Long.class);
        mvc.perform(delete("/api/products/{id}", legacyId)).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mvc.perform(get("/api/products/{id}", legacyId)).andExpect(status().isNotFound());
        mvc.perform(put("/api/products/{id}", legacyId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/products/{id}", legacyId)).andExpect(status().isNotFound());
    }

    @Test
    void completeOrderLifecycleWorksThroughHttpAndPostgresql() throws Exception {
        ProductResponse first = product("0.10", 10);
        ProductResponse second = product("19.99", 5);
        String body = objectMapper.writeValueAsString(new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(first.id(), 3), new CreateOrderRequest.Item(second.id(), 2))));
        var result = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.totalAmount").value(40.28))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").isNumber())
                .andExpect(jsonPath("$.createdAt").exists()).andReturn();
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        assertThat(productService.getById(first.id()).stockQuantity()).isEqualTo(7);
        assertThat(productService.getById(second.id()).stockQuantity()).isEqualTo(3);

        mvc.perform(get("/api/orders/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.items[0].subtotal").value(0.30));
        mvc.perform(delete("/api/products/{id}", first.id())).andExpect(status().isConflict());
        mvc.perform(post("/api/orders/{id}/cancel", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/orders/{id}/cancel", id)).andExpect(status().isConflict());
        assertThat(productService.getById(first.id()).stockQuantity()).isEqualTo(10);
        assertThat(productService.getById(second.id()).stockQuantity()).isEqualTo(5);
        mvc.perform(delete("/api/products/{id}", first.id())).andExpect(status().isConflict());
    }

    @Test
    void missingResourcesAndInvalidQuantitiesHaveCorrectHttpStatuses() throws Exception {
        ProductResponse product = product("10.00", 5);
        mvc.perform(get("/api/orders/999")).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/999/cancel")).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":999,\"quantity\":1}]}"))
                .andExpect(status().isNotFound());
        for (int quantity : new int[]{-1, 0, 1_000_001}) {
            String invalid = "{\"items\":[{\"productId\":%d,\"quantity\":%d}]}"
                    .formatted(product.id(), quantity);
            mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors").isNotEmpty());
        }
        assertThat(orderRepository.count()).isZero();
        assertThat(productService.getById(product.id()).stockQuantity()).isEqualTo(5);
    }

    @Test
    void largeMonetaryValuesRoundTripExactlyWithoutOverflow() {
        ProductResponse expensive = product("999999999999.99", 1_000_000);
        ProductResponse cheap = product("0.10", 3);
        OrderResponse created = orderService.create(new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(expensive.id(), 1_000_000),
                new CreateOrderRequest.Item(cheap.id(), 3))));
        OrderResponse loaded = orderService.getById(created.id());
        assertThat(loaded.totalAmount()).isEqualByComparingTo("999999999999990000.30");
        assertThat(loaded.items().getFirst().subtotal()).isEqualByComparingTo("999999999999990000.00");
        assertThat(jdbc.queryForObject("SELECT total_amount FROM customer_orders WHERE id = ?",
                BigDecimal.class, created.id())).isEqualByComparingTo(loaded.totalAmount());
    }

    @Test
    void fulfillmentLifecyclePersistsWithoutFurtherStockOrMoneyChanges() throws Exception {
        ProductResponse product = product("9.99", 10);
        OrderResponse created = orderService.create(request(product.id(), 3));
        // Compare persisted snapshots: PostgreSQL stores microseconds, Instant can carry nanoseconds.
        OrderResponse persisted = orderService.getById(created.id());
        for (String action : List.of("confirm", "ship", "deliver")) {
            String body = mvc.perform(post("/api/orders/{id}/{action}", created.id(), action))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            OrderResponse result = objectMapper.readValue(body, OrderResponse.class);
            OrderStatus expected = targetStatus(action);
            assertThat(result).isEqualTo(new OrderResponse(persisted.id(), expected, persisted.totalAmount(),
                    persisted.createdAt(), persisted.items(), persisted.userId()));
            assertThat(orderService.getById(created.id()).status()).isEqualTo(expected);
            assertThat(productService.getById(product.id()).stockQuantity()).isEqualTo(7);
        }
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "CREATED,ship", "CREATED,deliver",
        "CONFIRMED,confirm", "CONFIRMED,deliver", "CONFIRMED,cancel",
        "SHIPPED,confirm", "SHIPPED,ship", "SHIPPED,cancel",
        "DELIVERED,confirm", "DELIVERED,ship", "DELIVERED,deliver", "DELIVERED,cancel",
        "CANCELLED,confirm", "CANCELLED,ship", "CANCELLED,deliver", "CANCELLED,cancel"
    })
    void invalidLifecycleActionReturns409AndLeavesDatabaseUnchanged(OrderStatus source, String action)
            throws Exception {
        ProductResponse product = product("9.99", 10);
        OrderResponse created = orderService.create(request(product.id(), 3));
        advanceTo(created.id(), source);
        OrderResponse before = orderService.getById(created.id());
        int stockBefore = productService.getById(product.id()).stockQuantity();

        mvc.perform(post("/api/orders/{id}/{action}", created.id(), action))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));

        assertThat(orderService.getById(created.id())).isEqualTo(before);
        assertThat(productService.getById(product.id()).stockQuantity()).isEqualTo(stockBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "ship", "deliver"})
    void concurrentDuplicateLifecycleActionsHaveOneSuccessAndOneConflict(String action) throws Exception {
        ProductResponse product = product("9.99", 10);
        OrderResponse order = orderService.create(request(product.id(), 3));
        advanceTo(order.id(), sourceStatus(action));

        List<Boolean> outcomes = overlapTransactions(() -> {
            lifecycleAction(order.id(), action);
            return true;
        }, () -> {
            try {
                lifecycleAction(order.id(), action);
                return true;
            } catch (ConflictException exception) {
                assertThat(exception).hasMessageContaining("cannot transition");
                return false;
            }
        }, "customer_orders");

        assertThat(outcomes).containsExactly(true, false);
        assertThat(orderService.getById(order.id()).status()).isEqualTo(targetStatus(action));
        assertThat(productService.getById(product.id()).stockQuantity()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "cancel"})
    void concurrentConfirmAndCancelRespectTheCommittedWinner(String firstAction) throws Exception {
        ProductResponse product = product("9.99", 10);
        OrderResponse order = orderService.create(request(product.id(), 3));
        String secondAction = firstAction.equals("confirm") ? "cancel" : "confirm";

        List<Boolean> outcomes = overlapTransactions(() -> {
            lifecycleAction(order.id(), firstAction);
            return true;
        }, () -> {
            try {
                lifecycleAction(order.id(), secondAction);
                return true;
            } catch (ConflictException exception) {
                assertThat(exception).hasMessageContaining("from " + targetStatus(firstAction));
                return false;
            }
        }, "customer_orders");

        assertThat(outcomes).containsExactly(true, false);
        assertThat(orderService.getById(order.id()).status()).isEqualTo(targetStatus(firstAction));
        assertThat(productService.getById(product.id()).stockQuantity())
                .isEqualTo(firstAction.equals("cancel") ? 10 : 7);
    }

    @ParameterizedTest
    @CsvSource({"confirm,ship", "ship,deliver"})
    void concurrentAdjacentActionsCanBothSucceedInValidSerialOrder(String firstAction, String secondAction)
            throws Exception {
        ProductResponse product = product("9.99", 10);
        OrderResponse order = orderService.create(request(product.id(), 3));
        advanceTo(order.id(), sourceStatus(firstAction));

        List<Boolean> outcomes = overlapTransactions(() -> {
            lifecycleAction(order.id(), firstAction);
            return true;
        }, () -> {
            lifecycleAction(order.id(), secondAction);
            return true;
        }, "customer_orders");

        assertThat(outcomes).containsExactly(true, true);
        assertThat(orderService.getById(order.id()).status()).isEqualTo(targetStatus(secondAction));
        assertThat(productService.getById(product.id()).stockQuantity()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "ship", "deliver"})
    void failedLifecycleWriteRollsBackAndCanBeRetried(String action) {
        ProductResponse product = product("9.99", 10);
        OrderResponse order = orderService.create(request(product.id(), 3));
        advanceTo(order.id(), sourceStatus(action));
        OrderResponse before = orderService.getById(order.id());
        jdbc.execute("""
                CREATE FUNCTION test_reject_status_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Forced status write failure' USING ERRCODE = '23514';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_reject_status_update BEFORE UPDATE OF status ON customer_orders
                FOR EACH ROW EXECUTE FUNCTION test_reject_status_update()
                """);
        try {
            assertThatThrownBy(() -> lifecycleAction(order.id(), action))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_reject_status_update ON customer_orders");
            jdbc.execute("DROP FUNCTION IF EXISTS test_reject_status_update()");
        }
        assertThat(orderService.getById(order.id())).isEqualTo(before);
        assertThat(productService.getById(product.id()).stockQuantity()).isEqualTo(7);
        assertThat(lifecycleAction(order.id(), action).status()).isEqualTo(targetStatus(action));
    }

    @Test
    void lifecycleMigrationUpgradesV2WithoutChangingExistingOrdersOrInventory() {
        String schema = "lifecycle_upgrade";
        try {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("2").load().migrate();
            jdbc.execute("INSERT INTO lifecycle_upgrade.products(name,price,stock_quantity) VALUES ('Existing',10,8)");
            jdbc.execute("""
                    INSERT INTO lifecycle_upgrade.customer_orders(status,total_amount,created_at)
                    VALUES ('CREATED',10,now()), ('CANCELLED',10,now())
                    """);
            jdbc.execute("""
                    INSERT INTO lifecycle_upgrade.order_items(order_id,product_id,quantity,unit_price,subtotal)
                    VALUES (1,1,1,10,10), (2,1,1,10,10)
                    """);

            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).load().migrate();

            assertThat(jdbc.queryForList("SELECT status FROM lifecycle_upgrade.customer_orders ORDER BY id", String.class))
                    .containsExactly("CREATED", "CANCELLED");
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM lifecycle_upgrade.products WHERE id=1", Integer.class))
                    .isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM lifecycle_upgrade.order_items", Long.class)).isEqualTo(2);
            jdbc.update("UPDATE lifecycle_upgrade.customer_orders SET status='CONFIRMED' WHERE id=1");
            assertThatThrownBy(() -> jdbc.update("UPDATE lifecycle_upgrade.customer_orders SET status='PAID' WHERE id=1"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS lifecycle_upgrade CASCADE");
        }
    }

    private void advanceTo(Long id, OrderStatus status) {
        if (status == OrderStatus.CANCELLED) {
            orderService.cancel(id);
            return;
        }
        if (status != OrderStatus.CREATED) {
            orderService.confirm(id);
        }
        if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED) {
            orderService.ship(id);
        }
        if (status == OrderStatus.DELIVERED) {
            orderService.deliver(id);
        }
    }

    private OrderResponse lifecycleAction(Long id, String action) {
        return switch (action) {
            case "confirm" -> orderService.confirm(id);
            case "ship" -> orderService.ship(id);
            case "deliver" -> orderService.deliver(id);
            case "cancel" -> orderService.cancel(id);
            default -> throw new IllegalArgumentException("Unsupported action in test: " + action);
        };
    }

    private OrderStatus sourceStatus(String action) {
        return switch (action) {
            case "confirm" -> OrderStatus.CREATED;
            case "ship" -> OrderStatus.CONFIRMED;
            case "deliver" -> OrderStatus.SHIPPED;
            default -> throw new IllegalArgumentException("Unsupported action in test: " + action);
        };
    }

    private OrderStatus targetStatus(String action) {
        return switch (action) {
            case "confirm" -> OrderStatus.CONFIRMED;
            case "ship" -> OrderStatus.SHIPPED;
            case "deliver" -> OrderStatus.DELIVERED;
            case "cancel" -> OrderStatus.CANCELLED;
            default -> throw new IllegalArgumentException("Unsupported action in test: " + action);
        };
    }

    private void rejectStockUpdates() {
        jdbc.execute("""
                CREATE FUNCTION test_reject_stock_update() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Forced inventory write failure' USING ERRCODE = '23514';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_reject_stock_update BEFORE UPDATE OF stock_quantity ON products
                FOR EACH ROW EXECUTE FUNCTION test_reject_stock_update()
                """);
    }

    private void allowStockUpdates() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_reject_stock_update ON products");
        jdbc.execute("DROP FUNCTION IF EXISTS test_reject_stock_update()");
    }

    private ProductResponse product(String price, int stock) {
        return productService.create(new ProductRequest("Product", null, new BigDecimal(price), stock));
    }

    private CreateOrderRequest request(Long id, int quantity) {
        return new CreateOrderRequest(List.of(new CreateOrderRequest.Item(id, quantity)));
    }

    private List<Boolean> overlapTransactions(Supplier<Boolean> first, Callable<Boolean> second,
                                               String lockedTable) throws Exception {
        CountDownLatch firstHasWritten = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        try (var executor = authenticatedExecutor(2)) {
            var one = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                Boolean result = first.get();
                firstHasWritten.countDown();
                await(allowFirstCommit);
                return result;
            }));
            try {
                assertThat(firstHasWritten.await(10, TimeUnit.SECONDS)).isTrue();
                var two = executor.submit(second);
                try {
                    assertThat(waitForBlockedStatement(lockedTable))
                            .as("second request waits on a PostgreSQL row lock on %s", lockedTable).isTrue();
                    assertThat(two.isDone()).isFalse();
                } finally {
                    allowFirstCommit.countDown();
                }
                return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            } finally {
                allowFirstCommit.countDown();
            }
        }
    }

    private boolean waitForBlockedStatement(String table) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            Long waiters = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock' AND query LIKE ?
                    """, Long.class, "%" + table + "%");
            if (waiters != null && waiters > 0) {
                return true;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to commit the first transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test was interrupted", exception);
        }
    }

    private List<Boolean> raceTasks(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = authenticatedExecutor(2)) {
            var one = executor.submit(gated(first, ready, start));
            var two = executor.submit(gated(second, ready, start));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            return List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
    }

    private Callable<Boolean> gated(Callable<Boolean> task, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent task");
            }
            return task.call();
        };
    }
}
