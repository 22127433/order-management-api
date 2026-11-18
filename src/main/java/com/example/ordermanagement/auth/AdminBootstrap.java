package com.example.ordermanagement.auth;

import com.example.ordermanagement.common.ConflictException;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.bootstrap-admin.enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final Validator validator;
    private final String email;
    private final String password;
    public AdminBootstrap(UserRepository users, PasswordEncoder passwords, Validator validator,
                          @Value("${app.bootstrap-admin.email}") String email,
                          @Value("${app.bootstrap-admin.password}") String password) {
        this.users = users;
        this.passwords = passwords;
        this.validator = validator;
        this.email = email;
        this.password = password;
    }
    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        RegisterRequest credentials = new RegisterRequest(email, password);
        if (!validator.validate(credentials).isEmpty()) {
            // Do not include rejected values or password-bearing binding exceptions in startup logs.
            throw new IllegalStateException("Bootstrap admin email/password configuration is invalid");
        }
        AuthService.validatePasswordBytes(password);
        var existing = users.findByEmail(credentials.email());
        if (existing.isPresent()) {
            if (existing.get().getRole() != Role.ADMIN) {
                throw new ConflictException("Admin bootstrap refuses to promote an existing CUSTOMER");
            }
            return; // Never reset an existing admin's password silently.
        }
        users.saveAndFlush(AppUser.admin(credentials.email(), passwords.encode(password)));
    }
}
