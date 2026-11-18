package com.example.ordermanagement.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class JwtTestProperties {
    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret-base64", TestSecurity::secret);
        registry.add("app.bootstrap-admin.enabled", () -> false);
    }
}
