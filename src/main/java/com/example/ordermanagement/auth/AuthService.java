package com.example.ordermanagement.auth;

import com.example.ordermanagement.common.BadRequestException;
import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.security.TokenService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokens;
    private final Validator validator;

    public AuthService(UserRepository users, PasswordEncoder passwords, AuthenticationManager authenticationManager,
                       TokenService tokens, Validator validator) {
        this.users = users;
        this.passwords = passwords;
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
        this.validator = validator;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        validate(request);
        validatePasswordBytes(request.password());
        if (users.existsByEmail(request.email())) { throw new ConflictException("Email is already registered"); }
        return UserResponse.from(users.saveAndFlush(AppUser.customer(request.email(), passwords.encode(request.password()))));
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        validate(request);
        validatePasswordBytes(request.password());
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        AppUser user = users.findById(Long.valueOf(authentication.getName()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        return tokens.issue(user);
    }

    private void validate(Object request) {
        if (request == null) { throw new BadRequestException("Request is required"); }
        var violations = validator.validate(request);
        if (!violations.isEmpty()) { throw new ConstraintViolationException(violations); }
    }

    static void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadRequestException("Password must not exceed 72 UTF-8 bytes");
        }
    }
}
