package com.example.ordermanagement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.auth.UserRepository;
import com.example.ordermanagement.inventory.RestockRequest;
import com.example.ordermanagement.inventory.AdjustStockRequest;
import com.example.ordermanagement.inventory.StockMovementQuery;
import com.example.ordermanagement.inventory.StockMovementResponse;
import com.example.ordermanagement.inventory.StockMovementType;
import com.example.ordermanagement.order.CreateOrderRequest;
import com.example.ordermanagement.order.OrderResponse;
import com.example.ordermanagement.order.OrderService;
import com.example.ordermanagement.order.OrderStatus;
import com.example.ordermanagement.product.ProductRequest;
import com.example.ordermanagement.product.ProductResponse;
import com.example.ordermanagement.product.ProductService;
import com.example.ordermanagement.product.ProductAvailabilityRequest;
import com.example.ordermanagement.security.TokenService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
class InventoryManagementIT extends com.example.ordermanagement.support.AuthenticatedIntegrationTest {
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
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository users;
    @Autowired private TokenService tokens;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE stock_movements, order_items, customer_orders, products RESTART IDENTITY CASCADE");
    }

    @Test
    void commitsAllFourMovementTypesWithStockAndReferences() throws Exception {
        ProductResponse product = product(10);
        mvc.perform(post("/api/products/{id}/restock", product.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5,\"reason\":\" Supplier delivery \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQuantity").value(15));
        OrderResponse order = orders.create(request(product.id(), 3));
        orders.cancel(order.id());
        var history = history(product.id());
        assertThat(history).extracting(StockMovementResponse::type).containsExactly(StockMovementType.INITIAL_STOCK,
                StockMovementType.MANUAL_RESTOCK, StockMovementType.ORDER_DEDUCTION, StockMovementType.ORDER_CANCELLATION);
        assertThat(history).extracting(StockMovementResponse::quantityDelta).containsExactly(10, 5, -3, 3);
        assertThat(history).extracting(StockMovementResponse::stockBefore).containsExactly(0, 10, 15, 12);
        assertThat(history).extracting(StockMovementResponse::stockAfter).containsExactly(10, 15, 12, 15);
        assertThat(history.get(1).reason()).isEqualTo("Supplier delivery");
        assertThat(history.get(2).referenceOrderId()).isEqualTo(order.id());
        assertThat(history.get(3).referenceOrderId()).isEqualTo(order.id());
        assertThat(history).allSatisfy(m -> assertThat(m.createdAt()).isNotNull());
        assertThatThrownBy(() -> orders.cancel(order.id())).isInstanceOf(ConflictException.class);
        assertThat(history(product.id())).isEqualTo(history);
        assertChain(product.id());
    }

    @Test
    void signedAdjustmentsCommitWithHistoryAndKeepRestockCompatible() throws Exception {
        ProductResponse product = product(10);
        mvc.perform(post("/api/products/{id}/adjust-stock", product.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-3,\"reason\":\" Damaged products \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQuantity").value(7));
        mvc.perform(post("/api/products/{id}/adjust-stock", product.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":5,\"reason\":\"Stock count correction\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQuantity").value(12));
        mvc.perform(post("/api/products/{id}/restock", product.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2,\"reason\":\"Delivery\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQuantity").value(14));
        var history = history(product.id());
        assertThat(history).extracting(StockMovementResponse::type).containsExactly(StockMovementType.INITIAL_STOCK,
                StockMovementType.MANUAL_ADJUSTMENT, StockMovementType.MANUAL_ADJUSTMENT, StockMovementType.MANUAL_RESTOCK);
        assertThat(history).extracting(StockMovementResponse::quantityDelta).containsExactly(10, -3, 5, 2);
        assertThat(history.get(1).reason()).isEqualTo("Damaged products");
        assertThat(history.subList(1, 3)).allSatisfy(m -> {
            assertThat(m.referenceOrderId()).isNull();
            assertThat(m.createdAt()).isNotNull();
        });
        mvc.perform(get("/api/products/{id}/stock-movements", product.id()).param("size", "2").param("sort", "createdAt,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[1].type").value("MANUAL_ADJUSTMENT"))
                .andExpect(jsonPath("$.content[1].quantityDelta").value(-3))
                .andExpect(jsonPath("$.content[1].stockBefore").value(10))
                .andExpect(jsonPath("$.content[1].stockAfter").value(7));
        assertChain(product.id());
    }

    @ParameterizedTest
    @ValueSource(ints = {-11, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void outOfRangeAdjustmentReturns409WithoutStockOrHistoryChanges(int delta) throws Exception {
        ProductResponse product = product(10);
        var before = history(product.id());
        mvc.perform(post("/api/products/{id}/adjust-stock", product.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":" + delta + ",\"reason\":\"Correction\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        assertThat(products.getById(product.id()).stockQuantity()).isEqualTo(10);
        assertThat(history(product.id())).isEqualTo(before);
    }

    @Test
    void adjustmentCanReachZeroAndMaximumAndDoesNotReactivateProduct() {
        ProductResponse product = product(10);
        products.changeAvailability(product.id(), new ProductAvailabilityRequest(false));
        assertThat(products.adjustStock(product.id(), new AdjustStockRequest(-10, "Write off all damaged items")).stockQuantity()).isZero();
        var maximum = products.adjustStock(product.id(), new AdjustStockRequest(Integer.MAX_VALUE, "Stock count correction"));
        assertThat(maximum.stockQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(maximum.active()).isFalse();
        assertThat(history(product.id())).extracting(StockMovementResponse::stockAfter).containsExactly(10, 0, Integer.MAX_VALUE);
    }

    @Test
    void customerAndAnonymousCannotAdjustStockAndMethodSecurityIsEnforced() throws Exception {
        ProductResponse product = product(10);
        long customerId = jdbc.queryForObject("""
                INSERT INTO app_users(email,password_hash,role,created_at) VALUES (?,?,'CUSTOMER',now())
                ON CONFLICT(email) DO UPDATE SET email=EXCLUDED.email RETURNING id
                """, Long.class, "inventory-customer@example.com", TestSecurity.PASSWORD_HASH);
        String token = tokens.issue(users.findById(customerId).orElseThrow()).accessToken();
        mvc.perform(post("/api/products/{id}/adjust-stock", product.id())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-1,\"reason\":\"Damaged\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/products/{id}/adjust-stock", product.id())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-1,\"reason\":\"Damaged\"}"))
                .andExpect(status().isUnauthorized());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                Long.toString(customerId), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        try {
            assertThatThrownBy(() -> products.adjustStock(product.id(), new AdjustStockRequest(-1, "Damaged")))
                    .isInstanceOf(AccessDeniedException.class);
        } finally { authenticateAdmin(); }
        assertThat(products.getById(product.id()).stockQuantity()).isEqualTo(10);
        assertThat(history(product.id())).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void adjustmentAndOrderSerializeInEitherLockOrderWithoutLostStock(boolean adjustmentFirst) throws Exception {
        ProductResponse product = product(10);
        Supplier<Boolean> adjustment = () -> {
            products.adjustStock(product.id(), new AdjustStockRequest(-3, "Damaged products"));
            return true;
        };
        Supplier<Boolean> purchase = () -> { orders.create(request(product.id(), 4)); return true; };
        assertThat(overlap(adjustmentFirst ? adjustment : purchase,
                () -> (adjustmentFirst ? purchase : adjustment).get())).containsExactly(true, true);
        assertThat(products.getById(product.id()).stockQuantity()).isEqualTo(3);
        assertThat(history(product.id())).hasSize(3);
        assertChain(product.id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void competingAdjustmentAndOrderRevalidateStockAfterWaitingForLock(boolean adjustmentFirst) throws Exception {
        ProductResponse product = product(10);
        assertThat(overlap(() -> {
            if (adjustmentFirst) { products.adjustStock(product.id(), new AdjustStockRequest(-8, "Damaged products")); }
            else { orders.create(request(product.id(), 8)); }
            return true;
        }, () -> {
            assertThatThrownBy(() -> {
                if (adjustmentFirst) { orders.create(request(product.id(), 3)); }
                else { products.adjustStock(product.id(), new AdjustStockRequest(-3, "Damaged products")); }
            }).isInstanceOf(ConflictException.class);
            return false;
        })).containsExactly(true, false);
        assertThat(products.getById(product.id()).stockQuantity()).isEqualTo(2);
        assertThat(history(product.id())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class)).isEqualTo(adjustmentFirst ? 0 : 1);
        assertChain(product.id());
    }

    @Test
    void v8MigrationPreservesAllExistingMovementTypesAndAcceptsBothAdjustmentSigns() {
        String schema = "adjustment_upgrade";
        try {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("7").load().migrate();
            jdbc.execute("INSERT INTO adjustment_upgrade.products(name,price,stock_quantity) VALUES ('Legacy',1,15)");
            jdbc.execute("INSERT INTO adjustment_upgrade.customer_orders(status,total_amount,created_at) VALUES ('CANCELLED',3,now())");
            jdbc.execute("""
                    INSERT INTO adjustment_upgrade.stock_movements(product_id,movement_type,quantity_delta,stock_before,stock_after,reference_order_id,reason,created_at)
                    VALUES (1,'INITIAL_STOCK',10,0,10,NULL,NULL,now()),
                           (1,'MANUAL_RESTOCK',5,10,15,NULL,'Delivery',now()),
                           (1,'ORDER_DEDUCTION',-3,15,12,1,NULL,now()),
                           (1,'ORDER_CANCELLATION',3,12,15,1,NULL,now())
                    """);
            var before = jdbc.queryForList("SELECT * FROM adjustment_upgrade.stock_movements ORDER BY id");
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForList("SELECT * FROM adjustment_upgrade.stock_movements ORDER BY id")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM adjustment_upgrade.products", Integer.class)).isEqualTo(15);
            jdbc.execute("""
                    INSERT INTO adjustment_upgrade.stock_movements(product_id,movement_type,quantity_delta,stock_before,stock_after,reason,created_at)
                    VALUES (1,'MANUAL_ADJUSTMENT',-3,15,12,'Damaged products',now()),
                           (1,'MANUAL_ADJUSTMENT',2,12,14,'Stock count correction',now())
                    """);
            assertThat(jdbc.queryForList("SELECT quantity_delta FROM adjustment_upgrade.stock_movements WHERE movement_type='MANUAL_ADJUSTMENT' ORDER BY id", Integer.class))
                    .containsExactly(-3, 2);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS adjustment_upgrade CASCADE"); }
    }

    @ParameterizedTest
    @EnumSource(StockMovementType.class)
    void movementInsertFailureRollsBackStockProductAndOrder(StockMovementType type) {
        ProductResponse product = product(10);
        OrderResponse order = type == StockMovementType.ORDER_CANCELLATION ? orders.create(request(product.id(), 3)) : null;
        int before = products.getById(product.id()).stockQuantity();
        long count = countMovements();
        long orderCount = jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class);
        jdbc.execute("""
                CREATE FUNCTION test_reject_movement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Forced movement failure' USING ERRCODE = '23514'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER test_reject_movement BEFORE INSERT ON stock_movements FOR EACH ROW "
                + "WHEN (NEW.movement_type = '" + type.name() + "') EXECUTE FUNCTION test_reject_movement()");
        try {
            assertThatThrownBy(() -> perform(type, product.id(), order)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_reject_movement ON stock_movements");
            jdbc.execute("DROP FUNCTION IF EXISTS test_reject_movement()");
        }
        assertThat(products.getById(product.id()).stockQuantity()).isEqualTo(before);
        assertThat(countMovements()).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class)).isEqualTo(orderCount);
        if (order != null) {
            assertThat(orders.getById(order.id()).status()).isEqualTo(OrderStatus.CREATED);
        }
    }

    @ParameterizedTest
    @EnumSource(value = StockMovementType.class, names = {"ORDER_DEDUCTION", "ORDER_CANCELLATION", "MANUAL_RESTOCK", "MANUAL_ADJUSTMENT"})
    void stockUpdateFailureRollsBackMovementInserts(StockMovementType type) {
        ProductResponse product = product(10);
        OrderResponse order = type == StockMovementType.ORDER_CANCELLATION ? orders.create(request(product.id(), 3)) : null;
        int before = products.getById(product.id()).stockQuantity();
        var historyBefore = history(product.id());
        jdbc.execute("""
                CREATE FUNCTION test_reject_inventory_write() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Forced stock failure' USING ERRCODE = '23514'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER test_reject_inventory_write BEFORE UPDATE OF stock_quantity ON products "
                + "FOR EACH ROW EXECUTE FUNCTION test_reject_inventory_write()");
        try {
            assertThatThrownBy(() -> perform(type, product.id(), order)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_reject_inventory_write ON products");
            jdbc.execute("DROP FUNCTION IF EXISTS test_reject_inventory_write()");
        }
        assertThat(products.getById(product.id()).stockQuantity()).isEqualTo(before);
        assertThat(history(product.id())).isEqualTo(historyBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class)).isEqualTo(order == null ? 0 : 1);
        if (order != null) {
            assertThat(orders.getById(order.id()).status()).isEqualTo(OrderStatus.CREATED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"restock", "order", "cancel"})
    void concurrentRestockAndInventoryOperationKeepOneConsistentHistory(String operation) throws Exception {
        ProductResponse product = product(10);
        OrderResponse order = operation.equals("cancel") ? orders.create(request(product.id(), 3)) : null;
        List<Boolean> results = overlap(() -> {
            products.restock(product.id(), new RestockRequest(5, "First delivery"));
            return true;
        }, () -> {
            switch (operation) {
                case "restock" -> products.restock(product.id(), new RestockRequest(5, "Second delivery"));
                case "order" -> orders.create(request(product.id(), 3));
                case "cancel" -> orders.cancel(order.id());
                default -> throw new IllegalArgumentException(operation);
            }
            return true;
        });
        assertThat(results).containsExactly(true, true);
        assertThat(products.getById(product.id()).stockQuantity())
                .isEqualTo(operation.equals("restock") ? 20 : operation.equals("order") ? 12 : 15);
        assertThat(history(product.id())).hasSize(operation.equals("cancel") ? 4 : 3);
        assertChain(product.id());
    }

    @Test
    void concurrentRestocksAtIntegerLimitHaveOneSuccessAndNoOverflowMovement() throws Exception {
        ProductResponse product = product(Integer.MAX_VALUE - 1);
        List<Boolean> results = overlap(() -> {
            products.restock(product.id(), new RestockRequest(1, "Last available capacity"));
            return true;
        }, () -> {
            try {
                products.restock(product.id(), new RestockRequest(1, "Would overflow"));
                return true;
            } catch (ConflictException exception) {
                return false;
            }
        });
        assertThat(results).containsExactly(true, false);
        assertThat(history(product.id())).hasSize(2);
        assertThat(products.getById(product.id()).stockQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertChain(product.id());
    }

    @Test
    void historyPaginationUsesChronologicalIdTieBreakerAndProductFilter() throws Exception {
        long id = legacyProduct(13);
        jdbc.update("""
                INSERT INTO stock_movements(product_id,movement_type,quantity_delta,stock_before,stock_after,reason,created_at)
                SELECT ?, 'MANUAL_RESTOCK', 1, n-1, n, 'Fixture', '2026-01-01T00:00:00Z'::timestamptz
                FROM generate_series(11,13) AS n
                """, id);
        product(1); // Another product's opening entry must not be counted.
        mvc.perform(get("/api/products/{id}/stock-movements?page=1&size=1&sort=createdAt,asc", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/products/{id}/stock-movements?size=1", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(3));
        mvc.perform(get("/api/products/{id}/stock-movements?page=20&size=1", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/products/999/stock-movements")).andExpect(status().isNotFound());
    }

    @Test
    void putRejectsStockFieldsAndForeignKeysPreserveHistory() throws Exception {
        ProductResponse product = product(10);
        var before = history(product.id());
        mvc.perform(put("/api/products/{id}", product.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\",\"price\":1,\"stockQuantity\":5}"))
                .andExpect(status().isBadRequest());
        assertThat(products.getById(product.id()).stockQuantity()).isEqualTo(10);
        assertThat(history(product.id())).isEqualTo(before);
        assertThatThrownBy(() -> products.delete(product.id())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM products WHERE id=?", product.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        OrderResponse order = orders.create(request(product.id(), 1));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM customer_orders WHERE id=?", order.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countMovements()).isEqualTo(2);
    }

    @Test
    void legacyOrdersCanCancelWithoutInventingPastMovements() {
        long productId = legacyProduct(7);
        long orderId = jdbc.queryForObject("INSERT INTO customer_orders(status,total_amount,created_at) "
                + "VALUES ('CREATED',3,now()) RETURNING id", Long.class);
        jdbc.update("INSERT INTO order_items(order_id,product_id,quantity,unit_price,subtotal) VALUES (?,?,3,1,3)", orderId, productId);
        assertThat(history(productId)).isEmpty();
        orders.cancel(orderId);
        var history = history(productId);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().type()).isEqualTo(StockMovementType.ORDER_CANCELLATION);
        assertThat(history.getFirst().stockBefore()).isEqualTo(7);
        assertThat(history.getFirst().stockAfter()).isEqualTo(10);
    }

    @Test
    void migrationAddsEmptyJournalWithoutBackfillingV3Data() {
        String schema = "inventory_upgrade";
        try {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("3").load().migrate();
            jdbc.execute("INSERT INTO inventory_upgrade.products(name,price,stock_quantity) VALUES ('Legacy',1,7)");
            jdbc.execute("INSERT INTO inventory_upgrade.customer_orders(status,total_amount,created_at) VALUES ('CREATED',3,now())");
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_upgrade.stock_movements", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM inventory_upgrade.products", Integer.class)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT status FROM inventory_upgrade.customer_orders", String.class)).isEqualTo("CREATED");
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS inventory_upgrade CASCADE");
        }
    }

    @ParameterizedTest
    @EnumSource(value = StockMovementType.class, names = {"ORDER_DEDUCTION", "ORDER_CANCELLATION"})
    void secondProductMovementFailureRollsBackEveryProductAndEarlierMovement(StockMovementType type) {
        ProductResponse first = product(10);
        ProductResponse second = product(10);
        var request = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(first.id(), 3),
                new CreateOrderRequest.Item(second.id(), 3)));
        OrderResponse order = type == StockMovementType.ORDER_CANCELLATION ? orders.create(request) : null;
        var firstHistory = history(first.id());
        var secondHistory = history(second.id());
        int stockBefore = products.getById(first.id()).stockQuantity();
        jdbc.execute("""
                CREATE FUNCTION test_reject_second_movement() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Forced second movement failure' USING ERRCODE = '23514'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER test_reject_second_movement BEFORE INSERT ON stock_movements FOR EACH ROW "
                + "WHEN (NEW.product_id=" + second.id() + " AND NEW.movement_type='" + type.name()
                + "') EXECUTE FUNCTION test_reject_second_movement()");
        try {
            assertThatThrownBy(() -> {
                if (order == null) { orders.create(request); } else { orders.cancel(order.id()); }
            }).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_reject_second_movement ON stock_movements");
            jdbc.execute("DROP FUNCTION IF EXISTS test_reject_second_movement()");
        }
        assertThat(history(first.id())).isEqualTo(firstHistory);
        assertThat(history(second.id())).isEqualTo(secondHistory);
        assertThat(products.getById(first.id()).stockQuantity()).isEqualTo(stockBefore);
        assertThat(products.getById(second.id()).stockQuantity()).isEqualTo(stockBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class)).isEqualTo(order == null ? 0 : 1);
        if (order != null) { assertThat(orders.getById(order.id()).status()).isEqualTo(OrderStatus.CREATED); }
    }

    @Test
    void databaseRejectsUnbalancedWrongSignMissingReferenceAndDuplicateMovements() {
        ProductResponse product = product(10);
        OrderResponse order = orders.create(request(product.id(), 1));
        long before = countMovements();
        String insert = "INSERT INTO stock_movements(product_id,movement_type,quantity_delta,stock_before,stock_after,"
                + "reference_order_id,reason,created_at) VALUES (?,?,?,?,?,?,?,now())";
        List<Object[]> invalid = List.of(
                new Object[]{product.id(), "MANUAL_RESTOCK", 1, 10, 100, null, "Unbalanced"},
                new Object[]{product.id(), "MANUAL_RESTOCK", -1, 10, 9, null, "Wrong sign"},
                new Object[]{product.id(), "ORDER_DEDUCTION", -1, 10, 9, null, null},
                new Object[]{product.id(), "MANUAL_RESTOCK", 1, 10, 11, null, " "},
                new Object[]{product.id(), "INITIAL_STOCK", 10, 0, 10, null, null},
                new Object[]{product.id(), "ORDER_DEDUCTION", -1, 10, 9, order.id(), null},
                new Object[]{product.id(), "ORDER_DEDUCTION", Integer.MIN_VALUE, Integer.MAX_VALUE, 0, order.id(), null},
                new Object[]{product.id(), "MANUAL_ADJUSTMENT", 0, 10, 10, null, "Zero"},
                new Object[]{product.id(), "MANUAL_ADJUSTMENT", -1, 10, 9, order.id(), "Unexpected order"},
                new Object[]{product.id(), "MANUAL_ADJUSTMENT", -1, 10, 9, null, null},
                new Object[]{product.id(), "MANUAL_ADJUSTMENT", -1, 10, 9, null, " "},
                new Object[]{product.id(), "MANUAL_ADJUSTMENT", -1, 10, 9, null, "x".repeat(501)},
                new Object[]{product.id(), "MANUAL_ADJUSTMENT", -1, 0, -1, null, "Negative stock"},
                new Object[]{product.id(), "MANUAL_ADJUSTMENT", 1, Integer.MAX_VALUE, 0, null, "Overflow"},
                new Object[]{product.id(), "UNSUPPORTED_TYPE", -1, 10, 9, null, "Unknown type"},
                new Object[]{999L, "MANUAL_RESTOCK", 1, 0, 1, null, "Missing product"});
        for (Object[] values : invalid) {
            assertThatThrownBy(() -> jdbc.update(insert, values)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(countMovements()).isEqualTo(before);
    }

    private void perform(StockMovementType type, Long productId, OrderResponse order) {
        switch (type) {
            case INITIAL_STOCK -> product(2);
            case ORDER_DEDUCTION -> orders.create(request(productId, 3));
            case ORDER_CANCELLATION -> orders.cancel(order.id());
            case MANUAL_RESTOCK -> products.restock(productId, new RestockRequest(5, "Delivery"));
            case MANUAL_ADJUSTMENT -> products.adjustStock(productId, new AdjustStockRequest(-3, "Damaged products"));
        }
    }

    private ProductResponse product(int stock) {
        return products.create(new ProductRequest("Product", null, BigDecimal.ONE, stock));
    }

    private long legacyProduct(int stock) {
        return jdbc.queryForObject("INSERT INTO products(name,price,stock_quantity) VALUES ('Legacy',1,?) RETURNING id", Long.class, stock);
    }

    private CreateOrderRequest request(Long id, int quantity) {
        return new CreateOrderRequest(List.of(new CreateOrderRequest.Item(id, quantity)));
    }

    private List<StockMovementResponse> history(Long productId) {
        return products.stockMovements(productId, new StockMovementQuery(0, 100, "createdAt,asc")).content();
    }

    private long countMovements() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_movements", Long.class);
    }

    private void assertChain(Long productId) {
        long previous = 0;
        for (StockMovementResponse movement : history(productId)) {
            assertThat(movement.stockBefore()).isEqualTo(previous);
            previous += movement.quantityDelta();
            assertThat(movement.stockAfter()).isEqualTo(previous);
        }
        assertThat(products.getById(productId).stockQuantity()).isEqualTo(previous);
    }

    private List<Boolean> overlap(Supplier<Boolean> first, Callable<Boolean> second) throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (var executor = authenticatedExecutor(2)) {
            var one = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                boolean result = first.get();
                written.countDown();
                await(commit);
                return result;
            }));
            try {
                assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
                var two = executor.submit(second);
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    boolean blocked = false;
                    do {
                        Long waiters = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                                + "WHERE datname=current_database() AND pid<>pg_backend_pid() "
                                + "AND wait_event_type='Lock' AND query LIKE '%products%'", Long.class);
                        if (waiters != null && waiters > 0) { blocked = true; break; }
                        Thread.sleep(25);
                    } while (System.nanoTime() < deadline);
                    assertThat(blocked).as("second operation waits for a PostgreSQL product row lock").isTrue();
                    assertThat(two.isDone()).isFalse();
                } finally {
                    commit.countDown();
                }
                return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            } finally {
                commit.countDown();
            }
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Timed out waiting for commit"); }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
