package com.example.ordermanagement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ordermanagement.auth.UserRepository;
import com.example.ordermanagement.inventory.RestockRequest;
import com.example.ordermanagement.order.OrderService;
import com.example.ordermanagement.product.ProductService;
import com.example.ordermanagement.support.JwtTestProperties;
import com.example.ordermanagement.support.TestSecurity;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthenticationAuthorizationIT extends JwtTestProperties {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JwtDecoder decoder;
    @Autowired private JwtEncoder encoder;
    @Autowired private OrderService orders;
    @Autowired private ProductService products;
    private String admin;
    private String alice;
    private String bob;
    private long aliceId;
    private long bobId;

    @BeforeEach
    void identities() {
        SecurityContextHolder.clearContext();
        jdbc.execute("TRUNCATE order_idempotency,stock_movements,order_items,customer_orders,products,app_users RESTART IDENTITY CASCADE");
        // Explicit test fixture, not production seeding or a registration capability.
        jdbc.update("INSERT INTO app_users(email,password_hash,role,created_at) VALUES (?,?,'ADMIN',now())",
                "admin-fixture@example.com", TestSecurity.PASSWORD_HASH);
        aliceId = register("alice@example.com");
        bobId = register("bob@example.com");
        admin = login("admin-fixture@example.com");
        alice = login("alice@example.com");
        bob = login("bob@example.com");
    }

    @Test
    void registrationStoresSaltedBcryptAndReturnsOnlySafeCustomerFields() {
        var response = call(HttpMethod.POST, "/api/auth/register", null,
                Map.of("email", "  NEW@Example.com  ", "password", TestSecurity.PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().path("email").asText()).isEqualTo("new@example.com");
        assertThat(response.getBody().path("role").asText()).isEqualTo("CUSTOMER");
        assertThat(response.getBody().has("password")).isFalse();
        assertThat(response.getBody().has("passwordHash")).isFalse();
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_users WHERE email='new@example.com'", String.class);
        assertThat(hash).startsWith("$2a$12$").isNotEqualTo(TestSecurity.PASSWORD);
        assertThat(passwords.matches(TestSecurity.PASSWORD, hash)).isTrue();
        assertThat(hash).isNotEqualTo(jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id=?", String.class, aliceId));
    }

    @Test
    void duplicateNormalizedEmailAndClientSelectedAdminAreRejected() {
        assertThat(call(HttpMethod.POST, "/api/auth/register", null,
                Map.of("email", " ALICE@EXAMPLE.COM ", "password", TestSecurity.PASSWORD)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST, "/api/auth/register", null,
                Map.of("email", "escalate@example.com", "password", TestSecurity.PASSWORD, "role", "ADMIN"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_users WHERE role='ADMIN'", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationHasOneSuccessAndOneConflict() throws Exception {
        var pair = concurrent(() -> call(HttpMethod.POST, "/api/auth/register", null,
                        Map.of("email", "race@example.com", "password", TestSecurity.PASSWORD)),
                () -> call(HttpMethod.POST, "/api/auth/register", null,
                        Map.of("email", "RACE@example.com", "password", TestSecurity.PASSWORD)));
        assertThat(pair).extracting(r -> r.getStatusCode().value()).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_users WHERE email='race@example.com'", Long.class)).isEqualTo(1);
    }

    @Test
    void utf8PasswordLimitAndDtoValidationReturn400WithoutPersistingUser() {
        for (String password : List.of("short", "é".repeat(40), "x".repeat(73))) {
            assertThat(call(HttpMethod.POST, "/api/auth/register", null,
                    Map.of("email", "invalid@example.com", "password", password)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_users WHERE email='invalid@example.com'", Long.class)).isZero();
    }

    @Test
    void loginIsStatelessAndInvalidCredentialsUseSameGeneric401() {
        var loggedIn = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("email", "alice@example.com", "password", TestSecurity.PASSWORD));
        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loggedIn.getHeaders().getCacheControl()).contains("no-store");
        assertThat(loggedIn.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(loggedIn.getBody().path("expiresIn").asInt()).isEqualTo(900);
        assertThat(decoder.decode(loggedIn.getBody().path("accessToken").asText()).getSubject()).isEqualTo(Long.toString(aliceId));
        for (String email : List.of("alice@example.com", "missing@example.com")) {
            var failure = call(HttpMethod.POST, "/api/auth/login", null, Map.of("email", email, "password", "test-wrong-password"));
            assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(failure.getBody().path("message").asText()).isEqualTo("Invalid email or password");
        }
        assertThat(call(HttpMethod.GET, "/api/orders/1", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void customerOwnsCreatedOrderAndOtherCustomerCannotReadIt() {
        long product = product(10);
        var created = create(alice, product, 2, null);
        long order = created.getBody().path("id").asLong();
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().path("userId").asLong()).isEqualTo(aliceId);
        assertThat(jdbc.queryForObject("SELECT user_id FROM customer_orders WHERE id=?", Long.class, order)).isEqualTo(aliceId);
        assertThat(call(HttpMethod.GET, "/api/orders/" + order, alice, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/orders/" + order, admin, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/orders/" + order, bob, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requestCannotAssignAnotherOwner() {
        long product = product(10);
        var response = call(HttpMethod.POST, "/api/orders", alice,
                Map.of("items", List.of(Map.of("productId", product, "quantity", 1)), "userId", bobId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class)).isZero();
        assertThat(stock(product)).isEqualTo(10);
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/products", "PUT,/api/products/1", "DELETE,/api/products/1", "POST,/api/products/1/restock",
            "GET,/api/products/1/stock-movements", "POST,/api/orders/1/confirm", "POST,/api/orders/1/ship",
            "POST,/api/orders/1/deliver", "POST,/api/orders/1/cancel"})
    void customerIsForbiddenFromAdminOperationsEvenWithValidJwt(String method, String path) {
        assertThat(call(HttpMethod.valueOf(method), path, alice, Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminCanRestockAndAdvanceOrCancelCustomerOrdersWithoutChangingBusinessRules() {
        long product = product(10);
        long one = create(alice, product, 2, null).getBody().path("id").asLong();
        long two = create(bob, product, 1, null).getBody().path("id").asLong();
        assertThat(call(HttpMethod.POST, "/api/orders/" + one + "/cancel", admin, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/orders/" + one + "/cancel", admin, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        for (String action : List.of("confirm", "ship", "deliver")) {
            assertThat(call(HttpMethod.POST, "/api/orders/" + two + "/" + action, admin, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(stock(product)).isEqualTo(9);
        assertThat(call(HttpMethod.POST, "/api/products/" + product + "/restock", admin,
                Map.of("quantity", 5, "reason", "test delivery")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stock(product)).isEqualTo(14);
    }

    @Test
    void serviceMethodAuthorizationCannotBeBypassedByCallingBeansDirectly() {
        long product = product(10);
        long order = create(alice, product, 1, null).getBody().path("id").asLong();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(alice),
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        try {
            assertThatThrownBy(() -> products.restock(product, new RestockRequest(5, "test"))).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> orders.cancel(order)).isInstanceOf(AccessDeniedException.class);
            assertThat(orders.getById(order).userId()).isEqualTo(aliceId);
        } finally { SecurityContextHolder.clearContext(); }
        assertThat(stock(product)).isEqualTo(9);
    }

    @Test
    void sameIdempotencyKeyIsIsolatedPerUserAndStillReplaysWithoutExtraStockWrite() {
        long product = product(10);
        var first = create(alice, product, 2, "shared-client-key");
        var retry = create(alice, product, 2, "shared-client-key");
        var other = create(bob, product, 1, "shared-client-key");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(other.getBody().path("userId").asLong()).isEqualTo(bobId);
        assertThat(other.getBody().path("id")).isNotEqualTo(first.getBody().path("id"));
        assertThat(create(alice, product, 3, "shared-client-key").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stock(product)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_idempotency", Long.class)).isEqualTo(2);
    }

    @Test
    void concurrentSameUserAndKeyProducesOneOrderWithOneReplay() throws Exception {
        long product = product(1);
        var results = concurrent(() -> create(alice, product, 1, "concurrent"), () -> create(alice, product, 1, "concurrent"));
        assertThat(results).extracting(r -> r.getStatusCode().value()).containsExactlyInAnyOrder(201, 200);
        assertThat(results.getFirst().getBody()).isEqualTo(results.getLast().getBody());
        assertThat(stock(product)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_orders", Long.class)).isEqualTo(1);
    }

    @Test
    void unauthorizedAttemptDoesNotConsumeIdempotencyKeyOrChangeStock() {
        long product = product(1);
        assertThat(create(null, product, 1, "not-consumed").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_idempotency", Long.class)).isZero();
        assertThat(stock(product)).isEqualTo(1);
        assertThat(create(alice, product, 1, "not-consumed").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void legacyUnownedOrdersAreAdminOnlyAndCanStillBeCancelled() {
        long legacy = jdbc.queryForObject("INSERT INTO customer_orders(status,total_amount,created_at) VALUES ('CREATED',0,now()) RETURNING id", Long.class);
        assertThat(call(HttpMethod.GET, "/api/orders/" + legacy, alice, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/orders/" + legacy, admin, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/orders/" + legacy + "/cancel", admin, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "issuer", "audience", "signature", "missing-expiry", "deleted-user", "changed-role"})
    void invalidJwtOrStaleIdentityReturns401(String invalid) {
        String token;
        if (invalid.equals("deleted-user") || invalid.equals("changed-role")) {
            token = alice;
            if (invalid.equals("deleted-user")) { jdbc.update("DELETE FROM app_users WHERE id=?", aliceId); }
            else { jdbc.update("UPDATE app_users SET role='ADMIN' WHERE id=?", aliceId); }
        } else if (invalid.equals("signature")) {
            int start = alice.lastIndexOf('.') + 1;
            token = alice.substring(0, start) + (alice.charAt(start) == 'a' ? 'b' : 'a') + alice.substring(start + 1);
        } else {
            Instant now = Instant.now();
            var claims = JwtClaimsSet.builder().issuer(invalid.equals("issuer") ? "other" : "order-management-api")
                    .audience(List.of(invalid.equals("audience") ? "other" : "order-management-api"))
                    .subject(Long.toString(aliceId)).claim("role", "CUSTOMER").issuedAt(now.minusSeconds(300));
            if (!invalid.equals("missing-expiry")) { claims.expiresAt(invalid.equals("expired") ? now.minusSeconds(90) : now.plusSeconds(900)); }
            token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        }
        var response = call(HttpMethod.GET, "/api/orders/1", token, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).startsWith("Bearer");
        assertThat(response.getBody().toString()).doesNotContain(token, "passwordHash");
    }

    @Test
    void migrationPreservesV5OrdersReceiptsAndInventoryWithoutInventingUsersOrOwners() {
        String schema = "security_upgrade";
        try {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("5").load().migrate();
            jdbc.execute("INSERT INTO security_upgrade.products(name,price,stock_quantity) VALUES ('Legacy',1,7)");
            jdbc.execute("INSERT INTO security_upgrade.customer_orders(status,total_amount,created_at) VALUES ('CREATED',0,now())");
            jdbc.execute("INSERT INTO security_upgrade.order_idempotency(idempotency_key,request_payload,order_id,response_body) "
                    + "VALUES ('legacy-key','v1|1:1;',1,'{\"id\":1}')");
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM security_upgrade.app_users", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM security_upgrade.customer_orders WHERE user_id IS NULL", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT request_payload FROM security_upgrade.order_idempotency WHERE user_id IS NULL", String.class)).isEqualTo("v1|1:1;");
            assertThat(jdbc.queryForObject("SELECT response_body FROM security_upgrade.order_idempotency", String.class)).isEqualTo("{\"id\":1}");
            assertThat(jdbc.queryForObject("SELECT stock_quantity FROM security_upgrade.products", Integer.class)).isEqualTo(7);
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS security_upgrade CASCADE"); }
    }

    private long register(String email) {
        var response = call(HttpMethod.POST, "/api/auth/register", null, Map.of("email", email, "password", TestSecurity.PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("id").asLong();
    }
    private String login(String email) {
        var response = call(HttpMethod.POST, "/api/auth/login", null, Map.of("email", email, "password", TestSecurity.PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().path("accessToken").asText();
    }
    private long product(int stock) {
        var response = call(HttpMethod.POST, "/api/products", admin,
                Map.of("name", "Product", "price", new BigDecimal("1.25"), "stockQuantity", stock));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("id").asLong();
    }
    private int stock(long product) { return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?", Integer.class, product); }
    private ResponseEntity<JsonNode> create(String token, long product, int quantity, String key) {
        HttpHeaders headers = headers(token);
        if (key != null) { headers.set("Idempotency-Key", key); }
        return http.exchange("/api/orders", HttpMethod.POST, new HttpEntity<>(Map.of("items", List.of(Map.of("productId", product, "quantity", quantity))), headers), JsonNode.class);
    }
    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }
    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (token != null) { headers.setBearerAuth(token); }
        return headers;
    }
    private <T> List<T> concurrent(Callable<T> one, Callable<T> two) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<T> first = () -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Timeout"); } return one.call(); };
            Callable<T> second = () -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Timeout"); } return two.call(); };
            var a = executor.submit(first);
            var b = executor.submit(second);
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); } finally { start.countDown(); }
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }
}
