package com.example.ordermanagement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermanagement.auth.UserRepository;
import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.inventory.RestockRequest;
import com.example.ordermanagement.inventory.StockMovementQuery;
import com.example.ordermanagement.order.CreateOrderRequest;
import com.example.ordermanagement.order.OrderService;
import com.example.ordermanagement.order.OrderStatus;
import com.example.ordermanagement.product.ProductAvailabilityRequest;
import com.example.ordermanagement.product.ProductRequest;
import com.example.ordermanagement.product.ProductService;
import com.example.ordermanagement.product.UpdateProductRequest;
import com.example.ordermanagement.security.TokenService;
import com.example.ordermanagement.support.AuthenticatedIntegrationTest;
import com.example.ordermanagement.support.TestSecurity;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProductAvailabilityIT extends AuthenticatedIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ProductService products;
    @Autowired private OrderService orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private UserRepository users;
    @Autowired private TokenService tokens;
    @Autowired private JwtDecoder decoder;
    private long productId;
    private String customerToken;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE order_idempotency,stock_movements,order_items,customer_orders,products RESTART IDENTITY CASCADE");
        long customerId = jdbc.queryForObject("""
                INSERT INTO app_users(email,password_hash,role,created_at) VALUES (?,?, 'CUSTOMER',now())
                ON CONFLICT(email) DO UPDATE SET email=EXCLUDED.email RETURNING id
                """, Long.class, "availability-customer@example.com", TestSecurity.PASSWORD_HASH);
        customerToken = tokens.issue(users.findById(customerId).orElseThrow()).accessToken();
        productId = products.create(new ProductRequest("Keyboard", "Original", new BigDecimal("9.99"), 10)).id();
    }

    @Test
    void newProductDefaultsActiveAndRepeatedAvailabilityPatchOnlyChangesFlag() throws Exception {
        assertThat(active()).isTrue();
        var before = products.stockMovements(productId, new StockMovementQuery(0, 20, null));
        for (boolean value : List.of(false, false, true, true)) {
            mvc.perform(patch("/api/products/{id}/availability", productId).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"active\":" + value + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(value))
                    .andExpect(jsonPath("$.stockQuantity").value(10));
        }
        assertThat(products.stockMovements(productId, new StockMovementQuery(0, 20, null))).isEqualTo(before);
        assertThat(stock()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class)).isZero();
    }

    @Test
    void publicReadsNeverExposeInactiveEvenWithClientFilterOrAdminIdentity() throws Exception {
        setActive(false);
        mvc.perform(get("/api/products").with(anonymous()).param("active", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/products/{id}", productId).with(anonymous())).andExpect(status().isNotFound());
        mvc.perform(get("/api/products/{id}", productId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/products").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void adminCatalogFiltersAvailabilityAndCountsBeforePagination() throws Exception {
        setActive(false);
        long second = products.create(new ProductRequest("Keyboard two", null, new BigDecimal("19.99"), 5)).id();
        products.changeAvailability(second, new ProductAvailabilityRequest(false));
        products.create(new ProductRequest("Keyboard active", null, BigDecimal.ONE, 0));
        mvc.perform(get("/api/admin/products").param("active", "false").param("keyword", "keyboard")
                        .param("minPrice", "9.99").param("inStock", "true").param("size", "1").param("page", "1")
                        .param("sort", "price,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[0].active").value(false)).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/admin/products")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/admin/products").param("active", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/products")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void inactiveOrderFailsWithoutStockJournalOrIdempotencyWriteThenReactivationAllowsRetry() throws Exception {
        setActive(false);
        String body = "{\"items\":[{\"productId\":" + productId + ",\"quantity\":2}]}";
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + customerToken).header("Idempotency-Key", "availability-retry")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("inactive")));
        assertThat(stock()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_idempotency", Long.class)).isZero();
        assertThat(movements()).isEqualTo(1);
        setActive(true);
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + customerToken).header("Idempotency-Key", "availability-retry")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        setActive(false);
        // Replaying an already committed purchase is not a new order, even if availability later changes.
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + customerToken).header("Idempotency-Key", "availability-retry")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(stock()).isEqualTo(8);
        assertThat(movements()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class)).isEqualTo(1);
    }

    @Test
    void existingOrdersRemainReadableAndCancellableWhileProductIsInactive() throws Exception {
        String body = "{\"items\":[{\"productId\":" + productId + ",\"quantity\":2}]}";
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        long orderId = jdbc.queryForObject("SELECT id FROM customer_orders", Long.class);
        setActive(false);
        mvc.perform(get("/api/orders/{id}", orderId).header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].productId").value(productId))
                .andExpect(jsonPath("$.totalAmount").value(19.98));
        assertThat(orders.cancel(orderId).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock()).isEqualTo(10);
        assertThat(active()).isFalse();
        assertThat(movements()).isEqualTo(3);
        assertThatThrownBy(() -> products.delete(productId)).isInstanceOf(ConflictException.class);
    }

    @Test
    void adminsCanUpdateRestockAndReadHistoryWithoutReactivatingProduct() {
        setActive(false);
        assertThat(products.update(productId, new UpdateProductRequest("Renamed", null, BigDecimal.TEN)).active()).isFalse();
        assertThat(products.restock(productId, new RestockRequest(3, "Warehouse delivery")).active()).isFalse();
        assertThat(stock()).isEqualTo(13);
        assertThat(movements()).isEqualTo(2);
        assertThat(products.stockMovements(productId, new StockMovementQuery(0, 20, null)).totalElements()).isEqualTo(2);
        assertThatThrownBy(() -> products.delete(productId)).isInstanceOf(ConflictException.class);
    }

    @Test
    void customerAndAnonymousCannotUseAdminAvailabilityAndServiceChecksCannotBeBypassed() throws Exception {
        mvc.perform(patch("/api/products/{id}/availability", productId).header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/products").header("Authorization", "Bearer " + customerToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/products").with(anonymous())).andExpect(status().isUnauthorized());
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(customerToken),
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        try {
            assertThatThrownBy(() -> products.changeAvailability(productId, new ProductAvailabilityRequest(false)))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> products.findAllForAdmin(new com.example.ordermanagement.product.ProductSearchQuery(
                    null, null, null, null, 0, 20, null), false)).isInstanceOf(AccessDeniedException.class);
        } finally { authenticateAdmin(); }
        assertThat(active()).isTrue();
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void deactivationWinsLockAndWaitingOrderIsRejectedWithoutStockWrite() throws Exception {
        assertThat(overlap(() -> { setActive(false); return true; }, () -> {
            assertThatThrownBy(() -> orders.create(request())).isInstanceOf(ConflictException.class).hasMessageContaining("inactive");
            return false;
        }, false)).containsExactly(true, false);
        assertThat(active()).isFalse();
        assertThat(stock()).isEqualTo(10);
        assertThat(movements()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class)).isZero();
    }

    @Test
    void orderWinsLockAndDeactivationWaitsWithoutUndoingCommittedOrder() throws Exception {
        assertThat(overlap(() -> { orders.create(request()); return true; }, () -> { setActive(false); return true; }, false))
                .containsExactly(true, true);
        assertThat(active()).isFalse();
        assertThat(stock()).isEqualTo(9);
        assertThat(movements()).isEqualTo(2);
        long orderId = jdbc.queryForObject("SELECT id FROM customer_orders", Long.class);
        assertThat(orders.getById(orderId).status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void rolledBackDeactivationLetsWaitingOrderProceed() throws Exception {
        assertThat(overlap(() -> { setActive(false); return true; }, () -> { orders.create(request()); return true; }, true))
                .containsExactly(true, true);
        assertThat(active()).isTrue();
        assertThat(stock()).isEqualTo(9);
        assertThat(movements()).isEqualTo(2);
    }

    @Test
    void v7MigrationPreservesLegacyProductsOrdersAndMovementsAndDefaultsNewRowsActive() {
        String schema = "availability_upgrade";
        try {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("6").load().migrate();
            jdbc.execute("INSERT INTO availability_upgrade.products(name,price,stock_quantity) VALUES ('Legacy',9.99,7),('Unused',1,0)");
            jdbc.execute("INSERT INTO availability_upgrade.customer_orders(status,total_amount,created_at) VALUES ('CREATED',9.99,now())");
            jdbc.execute("INSERT INTO availability_upgrade.order_items(order_id,product_id,quantity,unit_price,subtotal) VALUES (1,1,1,9.99,9.99)");
            jdbc.execute("INSERT INTO availability_upgrade.stock_movements(product_id,movement_type,quantity_delta,stock_before,stock_after,created_at) "
                    + "VALUES (1,'INITIAL_STOCK',7,0,7,now())");
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForList("SELECT active FROM availability_upgrade.products ORDER BY id", Boolean.class)).containsExactly(true, true);
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM availability_upgrade.products WHERE id=1", Integer.class)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT total_amount FROM availability_upgrade.customer_orders", BigDecimal.class)).isEqualByComparingTo("9.99");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM availability_upgrade.order_items", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT stock_after FROM availability_upgrade.stock_movements", Integer.class)).isEqualTo(7);
            jdbc.execute("INSERT INTO availability_upgrade.products(name,price,stock_quantity) VALUES ('New SQL row',1,0)");
            assertThat(jdbc.queryForObject("SELECT active FROM availability_upgrade.products WHERE name='New SQL row'", Boolean.class)).isTrue();
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS availability_upgrade CASCADE"); }
    }

    private List<Boolean> overlap(Supplier<Boolean> first, Callable<Boolean> second, boolean rollbackFirst) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = authenticatedExecutor(2)) {
            var one = executor.submit(() -> new TransactionTemplate(transactions).execute(transaction -> {
                Boolean result = first.get();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Commit barrier timed out"); }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                if (rollbackFirst) { transaction.setRollbackOnly(); }
                return result;
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var two = executor.submit(second);
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    boolean waiting = false;
                    while (!waiting && System.nanoTime() < deadline) {
                        waiting = jdbc.queryForObject("""
                                SELECT count(*) > 0 FROM pg_stat_activity WHERE datname=current_database()
                                AND pid <> pg_backend_pid() AND wait_event_type='Lock' AND query LIKE '%products%'
                                """, Boolean.class);
                        if (!waiting) { Thread.sleep(25); }
                    }
                    assertThat(waiting).as("second operation really waits on PostgreSQL product row lock").isTrue();
                    assertThat(two.isDone()).isFalse();
                } finally { release.countDown(); }
                return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    private CreateOrderRequest request() { return new CreateOrderRequest(List.of(new CreateOrderRequest.Item(productId, 1))); }
    private void setActive(boolean active) { products.changeAvailability(productId, new ProductAvailabilityRequest(active)); }
    private boolean active() { return jdbc.queryForObject("SELECT active FROM products WHERE id=?", Boolean.class, productId); }
    private int stock() { return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, productId); }
    private long movements() { return jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE product_id=?", Long.class, productId); }
}
