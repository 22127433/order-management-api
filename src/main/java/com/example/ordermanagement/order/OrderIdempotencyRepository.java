package com.example.ordermanagement.order;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.stereotype.Repository;

/** PostgreSQL-specific arbitration; JdbcTemplate joins the existing JPA datasource transaction. */
@Repository
public class OrderIdempotencyRepository {
    private final JdbcTemplate jdbc;

    public OrderIdempotencyRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        // The default JDBC subclass translator does not classify PostgreSQL 55P03 (lock timeout).
        // Keep this vendor-specific mapping local; the advice already handles CannotAcquireLockException.
        this.jdbc.setExceptionTranslator(new SQLErrorCodeSQLExceptionTranslator("PostgreSQL"));
    }

    public boolean claim(Long userId, String key, String canonicalPayload) {
        // A competing uncommitted insert waits here, before any inventory locks are acquired.
        return jdbc.update("""
                INSERT INTO order_idempotency (user_id, idempotency_key, request_payload)
                VALUES (?, ?, ?) ON CONFLICT (user_id, idempotency_key) WHERE user_id IS NOT NULL DO NOTHING
                """, userId, key, canonicalPayload) == 1;
    }

    public StoredResult findCompleted(Long userId, String key) {
        // Separate statement: READ COMMITTED must take a fresh snapshot after ON CONFLICT waits.
        return jdbc.queryForObject("""
                SELECT request_payload, response_body FROM order_idempotency
                WHERE user_id = ? AND idempotency_key = ? AND order_id IS NOT NULL
                """, (rs, row) -> new StoredResult(rs.getString("request_payload"), rs.getString("response_body")), userId, key);
    }

    public void complete(Long userId, String key, Long orderId, String responseBody) {
        int changed = jdbc.update("""
                UPDATE order_idempotency SET order_id = ?, response_body = ?
                WHERE user_id = ? AND idempotency_key = ? AND order_id IS NULL
                """, orderId, responseBody, userId, key);
        if (changed != 1) {
            throw new IllegalStateException("Idempotency reservation was not completed");
        }
    }

    public record StoredResult(String canonicalPayload, String responseBody) { }
}
