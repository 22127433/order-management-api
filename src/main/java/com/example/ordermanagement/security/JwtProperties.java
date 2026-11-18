package com.example.ordermanagement.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.jwt")
public record JwtProperties(String secretBase64, String issuer, String audience, Duration accessTokenTtl) {
    public JwtProperties {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || accessTokenTtl == null || accessTokenTtl.compareTo(Duration.ofMinutes(1)) < 0
                || accessTokenTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("JWT issuer/audience are required; access token TTL must be between one minute and one hour");
        }
    }
    @Override public String toString() { return "JwtProperties[secretBase64=[PROTECTED]]"; }
}
