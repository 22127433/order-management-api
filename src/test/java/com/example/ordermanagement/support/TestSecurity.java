package com.example.ordermanagement.support;

import com.example.ordermanagement.auth.AppUser;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

public final class TestSecurity {
    private static final byte[] KEY = new byte[32];
    static { new SecureRandom().nextBytes(KEY); }
    public static final String PASSWORD = "test-" + UUID.randomUUID();
    public static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    private TestSecurity() { }
    public static String secret() { return Base64.getEncoder().encodeToString(KEY); }
    public static AppUser user(long id) {
        AppUser user = AppUser.customer("unit-" + id + "@example.com", PASSWORD_HASH);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
