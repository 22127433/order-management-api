package com.example.ordermanagement.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Existing business regressions use explicit test identities, never disabled production security. */
public abstract class AuthenticatedIntegrationTest extends JwtTestProperties {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate fixtureJdbc;
    protected MockMvc mvc;
    protected long adminId;

    @BeforeEach
    void authenticatedFixture() {
        adminId = fixtureJdbc.queryForObject("""
                INSERT INTO app_users(email,password_hash,role,created_at) VALUES (?,?, 'ADMIN', now())
                ON CONFLICT(email) DO UPDATE SET email=EXCLUDED.email RETURNING id
                """, Long.class, "admin-fixture@example.com", TestSecurity.PASSWORD_HASH);
        authenticateAdmin();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(get("/").with(user(Long.toString(adminId)).roles("ADMIN")))
                // MockMvc clears the thread context. These mixed HTTP/service regressions restore their fixture identity.
                .alwaysDo(result -> authenticateAdmin()).build();
    }

    protected void authenticateAdmin() { SecurityContextHolder.setContext(adminContext()); }

    private SecurityContext adminContext() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(Long.toString(adminId), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        return context;
    }

    protected ExecutorService authenticatedExecutor(int threads) {
        return new DelegatingSecurityContextExecutorService(Executors.newFixedThreadPool(threads), adminContext());
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }
}
