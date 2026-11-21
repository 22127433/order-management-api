package com.example.ordermanagement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermanagement.auth.UserRepository;
import com.example.ordermanagement.security.TokenService;
import com.example.ordermanagement.support.JwtTestProperties;
import com.example.ordermanagement.support.TestSecurity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real JWT filter, service proxies, JPA projection/count queries, and PostgreSQL; no repository mocks. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(OrderListingIT.SqlCaptureConfiguration.class)
class OrderListingIT extends JwtTestProperties {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final ThreadLocal<List<String>> SQL = ThreadLocal.withInitial(ArrayList::new);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCaptureConfiguration {
        @Bean
        HibernatePropertiesCustomizer captureListingSql() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector",
                    (StatementInspector) statement -> { SQL.get().add(statement); return statement; });
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TokenService tokens;
    @Autowired private ObjectMapper mapper;
    private String alice;
    private String bob;
    private String admin;
    private String emptyCustomer;
    private long aliceId;
    private long bobId;
    private long old;
    private long lower;
    private long tieLow;
    private long tieHigh;
    private long bobOld;
    private long bobLatest;
    private long legacy;

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE order_idempotency,stock_movements,order_items,customer_orders,products,app_users RESTART IDENTITY CASCADE");
        aliceId = account("alice@example.com", "CUSTOMER");
        bobId = account("bob@example.com", "CUSTOMER");
        long adminId = account("admin@example.com", "ADMIN");
        long emptyId = account("empty@example.com", "CUSTOMER");
        alice = token(aliceId);
        bob = token(bobId);
        admin = token(adminId);
        emptyCustomer = token(emptyId);
        old = order(aliceId, "CREATED", "2026-11-01T10:00:00Z");
        bobOld = order(bobId, "CREATED", "2026-11-05T10:00:00Z");
        lower = order(aliceId, "CONFIRMED", "2026-11-10T10:00:00Z");
        legacy = order(null, "SHIPPED", "2026-11-15T10:00:00Z");
        tieLow = order(aliceId, "CREATED", "2026-11-21T10:00:00Z");
        tieHigh = order(aliceId, "CANCELLED", "2026-11-21T10:00:00Z");
        bobLatest = order(bobId, "DELIVERED", "2026-11-22T10:00:00Z");
        long product = jdbc.queryForObject("INSERT INTO products(name,price,stock_quantity) VALUES ('Fixture',2.50,10) RETURNING id", Long.class);
        for (long order : List.of(old, bobOld, lower, legacy, tieLow, tieHigh, bobLatest)) {
            jdbc.update("INSERT INTO order_items(order_id,product_id,quantity,unit_price,subtotal) VALUES (?,?,1,2.50,2.50)", order, product);
        }
        SQL.get().clear();
    }

    @Test
    void customersOnlySeeTheirOwnOrdersAndCannotChooseAnotherOwner() throws Exception {
        assertThat(ids(list(alice))).containsExactly(tieHigh, tieLow, lower, old);
        assertThat(ids(list(bob))).containsExactly(bobLatest, bobOld);
        JsonNode spoofed = response(get("/api/orders").param("userId", Long.toString(bobId)), alice);
        assertThat(ids(spoofed)).containsExactly(tieHigh, tieLow, lower, old);
        assertThat(spoofed.path("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    void adminSeesEveryOwnerAndLegacyWithoutAnInnerUserJoin() throws Exception {
        JsonNode result = list(admin);
        assertThat(ids(result)).containsExactly(bobLatest, tieHigh, tieLow, legacy, lower, bobOld, old);
        assertThat(result.path("totalElements").asLong()).isEqualTo(7);
        assertThat(ids(response(get("/api/orders").param("status", "SHIPPED"), admin))).containsExactly(legacy);
        assertThat(ids(response(get("/api/orders").param("userId", Long.toString(aliceId)), admin)))
                .containsExactly(bobLatest, tieHigh, tieLow, legacy, lower, bobOld, old);
    }

    @Test
    void statusAndInclusiveTimeBoundsCombineWithOwnershipBeforePaging() throws Exception {
        JsonNode result = response(get("/api/orders").param("status", "CREATED")
                .param("from", "2026-11-01T10:00:00Z").param("to", "2026-11-21T10:00:00Z")
                .param("size", "1").param("page", "1"), alice);
        assertThat(ids(result)).containsExactly(old);
        assertThat(result.path("totalElements").asLong()).isEqualTo(2);
        assertThat(result.path("totalPages").asInt()).isEqualTo(2);
        assertThat(result.path("last").asBoolean()).isTrue();
        assertThat(ids(response(get("/api/orders").param("status", "CREATED"), admin)))
                .containsExactly(tieLow, bobOld, old);
    }

    @Test
    void oneSidedAndEqualTimeBoundsAreInclusiveAndNormalizeOffsets() throws Exception {
        assertThat(ids(response(get("/api/orders").param("from", "2026-11-21T17:00:00+07:00"), alice)))
                .containsExactly(tieHigh, tieLow);
        assertThat(ids(response(get("/api/orders").param("to", "2026-11-10T10:00:00Z"), alice)))
                .containsExactly(lower, old);
        assertThat(ids(response(get("/api/orders").param("from", "2026-11-10T17:00:00+07:00")
                .param("to", "2026-11-10T10:00:00Z"), alice))).containsExactly(lower);
    }

    @Test
    void databasePagesUseDescendingIdToBreakEqualTimestampTies() throws Exception {
        JsonNode first = response(get("/api/orders").param("size", "1"), alice);
        JsonNode second = response(get("/api/orders").param("size", "1").param("page", "1"), alice);
        assertThat(ids(first)).containsExactly(tieHigh);
        assertThat(ids(second)).containsExactly(tieLow);
        assertThat(first.path("totalElements").asLong()).isEqualTo(4);
        assertThat(first.path("totalPages").asInt()).isEqualTo(4);
        assertThat(first.path("last").asBoolean()).isFalse();
        assertThat(ids(response(get("/api/orders").param("sort", "id,asc"), alice)))
                .containsExactly(tieHigh, tieLow, lower, old);
    }

    @Test
    void emptyCustomerNoMatchesAndOutOfRangePageRetainCorrectTotals() throws Exception {
        JsonNode empty = list(emptyCustomer);
        assertThat(ids(empty)).isEmpty();
        assertThat(empty.path("totalElements").asLong()).isZero();
        assertThat(empty.path("totalPages").asInt()).isZero();
        assertThat(empty.path("last").asBoolean()).isTrue();
        JsonNode filtered = response(get("/api/orders").param("status", "DELIVERED"), alice);
        assertThat(ids(filtered)).isEmpty();
        assertThat(filtered.path("totalElements").asLong()).isZero();
        JsonNode beyond = response(get("/api/orders").param("page", "10").param("size", "2"), alice);
        assertThat(ids(beyond)).isEmpty();
        assertThat(beyond.path("totalElements").asLong()).isEqualTo(4);
        assertThat(beyond.path("totalPages").asInt()).isEqualTo(2);
        assertThat(beyond.path("page").asInt()).isEqualTo(10);
    }

    @Test
    void summarySelectAndCountUseDatabasePaginationWithoutLoadingItems() throws Exception {
        JsonNode page = response(get("/api/orders").param("size", "2").param("page", "1"), alice);
        assertThat(ids(page)).containsExactly(lower, old);
        for (JsonNode summary : page.path("content")) {
            assertThat(summary.size()).isEqualTo(4);
            assertThat(summary.has("items")).isFalse();
            assertThat(summary.path("totalAmount").decimalValue()).isEqualByComparingTo("2.50");
        }
        List<String> statements = SQL.get().stream().map(String::toLowerCase).toList();
        assertThat(statements).noneSatisfy(sql -> assertThat(sql).contains("order_items"));
        List<String> orderSql = statements.stream().filter(sql -> sql.contains("customer_orders")).toList();
        assertThat(orderSql).hasSize(2);
        assertThat(orderSql).allSatisfy(sql -> assertThat(sql).contains("user_id=?").doesNotContain(" join ", "for update"));
        assertThat(orderSql).anySatisfy(sql -> assertThat(sql).contains("count("));
        assertThat(orderSql).anySatisfy(sql -> {
            // Hibernate can order a DTO select by column positions rather than column names.
            // The result assertions above and tie-pagination test verify the actual ordering.
            assertThat(sql).contains("order by", "offset", "fetch first");
            assertThat(sql.substring(0, sql.indexOf(" from "))).doesNotContain("user_id");
        });
    }

    @Test
    void filteringPredicatesAppearInBothSqlSelectAndCount() throws Exception {
        response(get("/api/orders").param("status", "CREATED").param("from", "2026-11-01T00:00:00Z")
                .param("to", "2026-11-30T00:00:00Z").param("size", "1"), alice);
        assertThat(SQL.get().stream().filter(sql -> sql.contains("customer_orders")).toList())
                .hasSize(2).allSatisfy(sql -> assertThat(sql).contains("user_id=?", "status=?", "created_at>=?", "created_at<=?"));
    }

    @Test
    void listingDoesNotChangeDetailResponseOrAnyBusinessData() throws Exception {
        list(alice);
        mvc.perform(get("/api/orders/" + tieLow).header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.userId").value(aliceId)).andExpect(jsonPath("$.status").value("CREATED"));
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_idempotency", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class)).isEqualTo(7);
    }

    @Test
    void unauthenticatedRequestIs401AndInvalidFiltersAre400WithRealBackend() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders").header("Authorization", "Bearer " + alice).param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").header("Authorization", "Bearer " + alice)
                        .param("from", "2026-11-22T00:00:00Z").param("to", "2026-11-21T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    private long account(String email, String role) {
        return jdbc.queryForObject("INSERT INTO app_users(email,password_hash,role,created_at) VALUES (?,?,?,now()) RETURNING id",
                Long.class, email, TestSecurity.PASSWORD_HASH, role);
    }

    private String token(long id) { return tokens.issue(users.findById(id).orElseThrow()).accessToken(); }

    private long order(Long owner, String state, String time) {
        return jdbc.queryForObject("INSERT INTO customer_orders(user_id,status,total_amount,created_at) VALUES (?,?,?,?) RETURNING id",
                Long.class, owner, state, new BigDecimal("2.50"), Timestamp.from(Instant.parse(time)));
    }

    private JsonNode list(String token) throws Exception { return response(get("/api/orders"), token); }

    private JsonNode response(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mapper.readTree(mvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<Long> ids(JsonNode result) {
        List<Long> ids = new ArrayList<>();
        result.path("content").forEach(order -> ids.add(order.path("id").asLong()));
        return ids;
    }
}
