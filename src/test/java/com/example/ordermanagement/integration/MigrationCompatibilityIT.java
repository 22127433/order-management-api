package com.example.ordermanagement.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ordermanagement.support.TestSecurity;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL schema upgrades, independent of Spring's already-migrated application context. */
@Testcontainers
class MigrationCompatibilityIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connection() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void cleanDatabaseAppliesAllMigrationsWithoutCreatingDemoAccountsOrBusinessData() {
        String schema = "fresh_install";
        try {
            Flyway flyway = flyway(schema, null);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);
            flyway.validate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
            for (String table : List.of("app_users", "products", "customer_orders", "order_items", "stock_movements", "order_idempotency")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + "." + table, Long.class)).as(table).isZero();
            }
            jdbc.execute("INSERT INTO fresh_install.products(name,price,stock_quantity) VALUES ('Default active',1,0)");
            assertThat(jdbc.queryForObject("SELECT active FROM fresh_install.products", Boolean.class)).isTrue();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS fresh_install CASCADE"); }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void upgradesEveryV1ThroughV6BaselineWithoutRewritingExistingBusinessData(int baseline) {
        String schema = "upgrade_v" + baseline;
        try {
            assertThat(flyway(schema, Integer.toString(baseline)).migrate().migrationsExecuted).isEqualTo(baseline);
            jdbc.execute("INSERT INTO " + schema + ".products(name,description,price,stock_quantity) VALUES ('Legacy keyboard','Preserve me',9.99,7)");
            jdbc.execute("INSERT INTO " + schema + ".customer_orders(status,total_amount,created_at) VALUES ('CREATED',29.97,'2026-01-01T00:00:00Z')");
            jdbc.execute("INSERT INTO " + schema + ".order_items(order_id,product_id,quantity,unit_price,subtotal) VALUES (1,1,3,9.99,29.97)");
            if (baseline >= 4) {
                jdbc.execute("INSERT INTO " + schema + ".stock_movements(product_id,movement_type,quantity_delta,stock_before,stock_after,reference_order_id,created_at) "
                        + "VALUES (1,'INITIAL_STOCK',10,0,10,NULL,'2026-01-01T00:00:00Z'),(1,'ORDER_DEDUCTION',-3,10,7,1,'2026-01-01T00:01:00Z')");
            }
            if (baseline == 6) {
                jdbc.update("INSERT INTO " + schema + ".app_users(email,password_hash,role,created_at) VALUES (?,?,'CUSTOMER',now())",
                        "migration-customer@example.com", TestSecurity.PASSWORD_HASH);
                jdbc.execute("UPDATE " + schema + ".customer_orders SET user_id=1");
            }
            if (baseline >= 5) {
                jdbc.update("INSERT INTO " + schema + ".order_idempotency(idempotency_key,request_payload,order_id,response_body"
                                + (baseline == 6 ? ",user_id" : "") + ") VALUES ('legacy-key','v1|1:3;',1,?" + (baseline == 6 ? ",1" : "") + ")",
                        "{\"id\":1,\"status\":\"CREATED\",\"totalAmount\":29.97}");
            }
            var productBefore = jdbc.queryForList("SELECT id,name,description,price,stock_quantity FROM " + schema + ".products");
            var orderBefore = jdbc.queryForList("SELECT id,status,total_amount,created_at FROM " + schema + ".customer_orders");
            var itemsBefore = jdbc.queryForList("SELECT * FROM " + schema + ".order_items");
            var journalBefore = baseline >= 4 ? jdbc.queryForList("SELECT * FROM " + schema + ".stock_movements ORDER BY id") : List.of();
            var receiptsBefore = baseline >= 5 ? jdbc.queryForList("SELECT idempotency_key,request_payload,order_id,response_body,created_at FROM " + schema + ".order_idempotency") : List.of();
            var usersBefore = baseline == 6 ? jdbc.queryForList("SELECT * FROM " + schema + ".app_users") : List.of();

            Flyway upgrade = flyway(schema, null);
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(8 - baseline);
            upgrade.validate();
            assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("8");
            assertThat(jdbc.queryForList("SELECT id,name,description,price,stock_quantity FROM " + schema + ".products")).isEqualTo(productBefore);
            assertThat(jdbc.queryForList("SELECT id,status,total_amount,created_at FROM " + schema + ".customer_orders")).isEqualTo(orderBefore);
            assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".order_items")).isEqualTo(itemsBefore);
            assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".stock_movements ORDER BY id")).isEqualTo(journalBefore);
            assertThat(jdbc.queryForList("SELECT idempotency_key,request_payload,order_id,response_body,created_at FROM " + schema + ".order_idempotency")).isEqualTo(receiptsBefore);
            assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".app_users")).isEqualTo(usersBefore);
            assertThat(jdbc.queryForObject("SELECT active FROM " + schema + ".products", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT user_id FROM " + schema + ".customer_orders", Long.class)).isEqualTo(baseline == 6 ? 1L : null);
            if (baseline >= 5) {
                assertThat(jdbc.queryForObject("SELECT user_id FROM " + schema + ".order_idempotency", Long.class)).isEqualTo(baseline == 6 ? 1L : null);
            }
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }

    private Flyway flyway(String schema, String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema);
        if (target != null) { config.target(target); }
        return config.load();
    }
}
