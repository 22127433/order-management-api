package com.example.ordermanagement.security;

import com.example.ordermanagement.auth.Role;
import com.example.ordermanagement.auth.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {
    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(properties.secretBase64() == null ? "" : properties.secretBase64());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JWT_SECRET_BASE64 must be valid Base64");
        }
        if (bytes.length < 32) { throw new IllegalArgumentException("JWT_SECRET_BASE64 must encode at least 32 random bytes"); }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid;
            try {
                valid = Long.parseLong(jwt.getSubject()) > 0
                        && jwt.getAudience().contains(properties.audience())
                        && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                        && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                        && !jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(30));
                Role.valueOf(jwt.getClaimAsString("role"));
            } catch (RuntimeException exception) { valid = false; }
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token claims", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(properties.issuer()), claims));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    UserDetailsService userDetailsService(UserRepository users) {
        return email -> {
            var user = users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
            // The authentication name is the stable database ID, not a client-supplied identity.
            return User.withUsername(user.getId().toString()).password(user.getPasswordHash()).roles(user.getRole().name()).build();
        };
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
