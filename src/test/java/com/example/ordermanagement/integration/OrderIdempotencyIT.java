package com.example.ordermanagement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.inventory.RestockRequest;
import com.example.ordermanagement.order.CreateOrderRequest;
import com.example.ordermanagement.order.IdempotentOrderService;
import com.example.ordermanagement.order.OrderService;
import com.example.ordermanagement.order.OrderStatus;
import com.example.ordermanagement.product.ProductRequest;
import com.example.ordermanagement.product.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class OrderIdempotencyIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdempotentOrderService idempotent;
    @Autowired private OrderService orders;
    @Autowired private ProductService products;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE order_idempotency, stock_movements, order_items, customer_orders, products RESTART IDENTITY CASCADE");
    }

    @Test
    void firstHttpRequestCommitsKeyOrderStockAndMovement() throws Exception {
        long productId = product(10);
        String body = mvc.perform(post("/api/orders").header("Idempotency-Key", "first")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request(productId, 2))))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/orders/1"))
                .andExpect(header().string("Idempotency-Replayed", "false")).andReturn().getResponse().getContentAsString();
        assertCommitted(productId, 8);
        assertThat(jdbc.queryForObject("SELECT request_payload FROM order_idempotency", String.class)).isEqualTo("v1|1:2;");
        assertThat(jdbc.queryForObject("SELECT order_id FROM order_idempotency", Long.class)).isEqualTo(1L);
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT response_body FROM order_idempotency", String.class)))
                .isEqualTo(mapper.readTree(body));
    }

    @Test
    void retryHttpRequestReturnsOriginalSnapshotEvenAfterCancellationAndPriceChange() throws Exception {
        long productId = product(10);
        String request = mapper.writeValueAsString(request(productId, 2));
        String first = mvc.perform(post("/api/orders").header("Idempotency-Key", "retry")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        orders.cancel(mapper.readTree(first).get("id").asLong());
        products.update(productId, new ProductRequest("Updated", null, new BigDecimal("9.99"), 10));
        String retry = mvc.perform(post("/api/orders").header("Idempotency-Key", "retry")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string("Location", "/api/orders/1")).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(retry)).isEqualTo(mapper.readTree(first));
        assertThat(orders.getById(1L).status()).isEqualTo(OrderStatus.CANCELLED);
        assertCommitted(productId, 10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE movement_type='ORDER_CANCELLATION'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void changedPayloadReturns409AndLeavesOriginalResultIntact() throws Exception {
        long productId = product(10);
        var first = idempotent.create("conflict", request(productId, 2));
        mvc.perform(post("/api/orders").header("Idempotency-Key", "conflict")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request(productId, 3))))
                .andExpect(status().isConflict());
        assertThat(idempotent.create("conflict", request(productId, 2)).response()).isEqualTo(first.response());
        assertCommitted(productId, 8);
    }

    @Test
    void reorderedItemsWhitespaceAndJsonPropertyOrderReplaySameOrder() throws Exception {
        long one = product(10);
        long two = product(10);
        var first = idempotent.create("canonical", new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(one, 2), new CreateOrderRequest.Item(two, 3))));
        String json = "{ \"items\" : [{\"quantity\":3,\"productId\":" + two + "}, {\"quantity\":2,\"productId\":" + one + "}] }";
        String replay = mvc.perform(post("/api/orders").header("Idempotency-Key", "canonical")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(replay)).isEqualTo(mapper.readTree(mapper.writeValueAsString(first.response())));
        assertThat(products.getById(one).stockQuantity()).isEqualTo(8);
        assertThat(products.getById(two).stockQuantity()).isEqualTo(7);
        assertThat(count("customer_orders")).isEqualTo(1);
        assertThat(count("order_idempotency")).isEqualTo(1);
        assertThat(deductions()).isEqualTo(2);
    }

    @Test
    void simultaneousSameKeyWaitsForCommitThenReplaysWithoutExtraDeduction() throws Exception {
        long productId = product(2);
        var request = request(productId, 2);
        var pair = overlap(() -> idempotent.create("concurrent", request), () -> idempotent.create("concurrent", request), false);
        assertThat(pair.first().replayed()).isFalse();
        assertThat(pair.second().replayed()).isTrue();
        assertThat(pair.second().response()).isEqualTo(pair.first().response());
        assertCommitted(productId, 0);
    }

    @Test
    void simultaneousDifferentPayloadWaitsThenConflicts() throws Exception {
        long productId = product(10);
        var pair = overlap(() -> {
            idempotent.create("concurrent-conflict", request(productId, 2));
            return true;
        }, () -> {
            assertThatThrownBy(() -> idempotent.create("concurrent-conflict", request(productId, 3)))
                    .isInstanceOf(ConflictException.class);
            return true;
        }, false);
        assertThat(pair.first()).isTrue();
        assertThat(pair.second()).isTrue();
        assertCommitted(productId, 8);
    }

    @Test
    void waitingRequestTakesOverWhenFirstTransactionRollsBack() throws Exception {
        long productId = product(2);
        var request = request(productId, 2);
        var pair = overlap(() -> idempotent.create("takeover", request), () -> idempotent.create("takeover", request), true);
        assertThat(pair.first().replayed()).isFalse();
        assertThat(pair.second().replayed()).isFalse();
        assertThat(pair.second().response().id()).isNotEqualTo(pair.first().response().id());
        assertThat(jdbc.queryForObject("SELECT order_id FROM order_idempotency", Long.class)).isEqualTo(pair.second().response().id());
        assertCommitted(productId, 0);
    }

    @Test
    void inProgressKeyTimesOutWith409ThenCanReplayAfterOwnerCommits() throws Exception {
        long productId = product(2);
        var request = request(productId, 2);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var owner = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                var result = idempotent.create("busy", request);
                written.countDown();
                await(finish);
                return result;
            }));
            try {
                assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
                mvc.perform(post("/api/orders").header("Idempotency-Key", "busy")
                                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                        .andExpect(status().isConflict());
                assertThat(owner.isDone()).isFalse();
            } finally {
                finish.countDown();
            }
            assertThat(owner.get(10, TimeUnit.SECONDS).replayed()).isFalse();
        }
        assertThat(idempotent.create("busy", request).replayed()).isTrue();
        assertCommitted(productId, 0);
    }

    @Test
    void insufficientStockRollsBackKeyAndSamePayloadCanRetryAfterRestock() {
        long productId = product(1);
        var request = request(productId, 2);
        assertThatThrownBy(() -> idempotent.create("failed", request)).isInstanceOf(ConflictException.class);
        assertRolledBack(productId, 1);
        products.restock(productId, new RestockRequest(1, "Retry setup"));
        assertThat(idempotent.create("failed", request).replayed()).isFalse();
        assertCommitted(productId, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order_idempotency", "products"})
    void lateSqlFailureRollsBackKeyOrderStockAndJournalThenAllowsRetry(String table) {
        long productId = product(10);
        jdbc.execute("""
                CREATE FUNCTION test_reject_idempotent_write() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Forced late failure' USING ERRCODE = '23514'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER test_reject_idempotent_write BEFORE UPDATE ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION test_reject_idempotent_write()");
        try {
            assertThatThrownBy(() -> idempotent.create("late-failure", request(productId, 2)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_reject_idempotent_write ON " + table);
            jdbc.execute("DROP FUNCTION IF EXISTS test_reject_idempotent_write()");
        }
        assertRolledBack(productId, 10);
        assertThat(idempotent.create("late-failure", request(productId, 2)).replayed()).isFalse();
        assertCommitted(productId, 8);
    }

    @Test
    void databaseRefusesToCommitIncompleteReservation() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbc.update("INSERT INTO order_idempotency(idempotency_key,request_payload) VALUES ('incomplete','v1|1:1;')")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("order_idempotency")).isZero();
        long productId = product(1);
        assertThat(idempotent.create("incomplete", request(productId, 1)).replayed()).isFalse();
        assertCommitted(productId, 0);
    }

    @Test
    void keyIsCaseSensitiveAndNoHeaderStillCreatesDistinctOrders() throws Exception {
        long productId = product(10);
        var request = request(productId, 1);
        idempotent.create("Key", request);
        idempotent.create("key", request);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                    .andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotency-Replayed"));
        }
        assertThat(count("customer_orders")).isEqualTo(4);
        assertThat(count("order_idempotency")).isEqualTo(2);
        assertThat(deductions()).isEqualTo(4);
        assertThat(products.getById(productId).stockQuantity()).isEqualTo(6);
    }

    @Test
    void migrationPreservesLegacyOrdersAndDoesNotInventKeys() {
        String schema = "idempotency_upgrade";
        try {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("4").load().migrate();
            jdbc.execute("INSERT INTO idempotency_upgrade.customer_orders(status,total_amount,created_at) VALUES ('CREATED',0,now())");
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_upgrade.order_idempotency", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_upgrade.customer_orders", Long.class)).isEqualTo(1);
            // Check that the deferred trigger resolves the table in its own schema, not public.
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    jdbc.update("INSERT INTO idempotency_upgrade.order_idempotency(idempotency_key,request_payload) VALUES ('legacy','v1|1:1;')")))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS idempotency_upgrade CASCADE");
        }
    }

    private long product(int stock) {
        return products.create(new ProductRequest("Product", null, new BigDecimal("1.25"), stock)).id();
    }

    private CreateOrderRequest request(long id, int quantity) {
        return new CreateOrderRequest(List.of(new CreateOrderRequest.Item(id, quantity)));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long deductions() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE movement_type='ORDER_DEDUCTION'", Long.class);
    }

    private void assertCommitted(long productId, int expectedStock) {
        assertThat(count("order_idempotency")).isEqualTo(1);
        assertThat(count("customer_orders")).isEqualTo(1);
        assertThat(count("order_items")).isEqualTo(1);
        assertThat(deductions()).isEqualTo(1);
        assertThat(products.getById(productId).stockQuantity()).isEqualTo(expectedStock);
    }

    private void assertRolledBack(long productId, int expectedStock) {
        assertThat(count("order_idempotency")).isZero();
        assertThat(count("customer_orders")).isZero();
        assertThat(count("order_items")).isZero();
        assertThat(deductions()).isZero();
        assertThat(products.getById(productId).stockQuantity()).isEqualTo(expectedStock);
    }

    private <T> ConcurrentResults<T> overlap(Supplier<T> first, Supplier<T> second, boolean rollbackFirst) throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                T result = first.get();
                written.countDown();
                await(finish);
                if (rollbackFirst) { status.setRollbackOnly(); }
                return result;
            }));
            try {
                assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
                var two = executor.submit(second::get);
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    boolean blocked = false;
                    do {
                        Long waiters = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                                + "WHERE datname=current_database() AND pid<>pg_backend_pid() "
                                + "AND wait_event_type='Lock' AND query LIKE '%INSERT INTO order_idempotency%'", Long.class);
                        if (waiters != null && waiters > 0) { blocked = true; break; }
                        Thread.sleep(25);
                    } while (System.nanoTime() < deadline);
                    assertThat(blocked).as("second request waits on PostgreSQL unique-key arbitration").isTrue();
                    assertThat(two.isDone()).isFalse();
                } finally {
                    finish.countDown();
                }
                return new ConcurrentResults<>(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            } finally {
                finish.countDown();
            }
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Timed out waiting for transaction"); }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record ConcurrentResults<T>(T first, T second) { }
}
