package com.example.ordermanagement.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ordermanagement.support.TestSecurity;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;

class JwtConfigTest {
    private final JwtConfig config = new JwtConfig();
    private final JwtProperties properties = new JwtProperties(TestSecurity.secret(), "order-management-api", "order-management-api", Duration.ofMinutes(15));

    @org.junit.jupiter.params.ParameterizedTest
    @ValueSource(longs = {-1, 0, 30, 3601})
    void rejectsTokenLifetimeOutsideOneMinuteToOneHour(long seconds) {
        assertThatThrownBy(() -> new JwtProperties(TestSecurity.secret(), properties.issuer(), properties.audience(), Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuesHs256TokenWithStableUserIdRoleIssuerAudienceAndExpiry() {
        var key = config.jwtSigningKey(properties);
        var response = new TokenService(config.jwtEncoder(key), properties).issue(TestSecurity.user(7L));
        var jwt = config.jwtDecoder(key, properties).decode(response.accessToken());
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getAudience()).containsExactly(properties.audience());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(jwt.getClaims()).doesNotContainKeys("password", "passwordHash", "email");
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "issuer", "audience", "subject", "role", "no-expiry", "no-role", "future-issued"})
    void rejectsInvalidAccessTokenClaims(String invalid) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(invalid.equals("issuer") ? "other" : properties.issuer())
                .audience(List.of(invalid.equals("audience") ? "other" : properties.audience()))
                .subject(invalid.equals("subject") ? "not-an-id" : "1")
                .issuedAt(invalid.equals("expired") ? now.minusSeconds(300) : invalid.equals("future-issued") ? now.plusSeconds(300) : now);
        if (!invalid.equals("no-expiry")) { claims.expiresAt(invalid.equals("expired") ? now.minusSeconds(120) : now.plusSeconds(900)); }
        if (!invalid.equals("no-role")) { claims.claim("role", invalid.equals("role") ? "SUPERUSER" : "CUSTOMER"); }
        var key = config.jwtSigningKey(properties);
        String token = config.jwtEncoder(key).encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        assertThatThrownBy(() -> config.jwtDecoder(key, properties).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTamperedSignature() {
        var key = config.jwtSigningKey(properties);
        String token = new TokenService(config.jwtEncoder(key), properties).issue(TestSecurity.user(1L)).accessToken();
        int signatureStart = token.lastIndexOf('.') + 1;
        String tampered = token.substring(0, signatureStart) + (token.charAt(signatureStart) == 'a' ? 'b' : 'a') + token.substring(signatureStart + 1);
        assertThatThrownBy(() -> config.jwtDecoder(key, properties).decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void decoderRejectsOtherAlgorithmsEvenUsingTheSameKeyMaterial() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        var key = new SecretKeySpec(bytes, "HmacSHA256");
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience())).subject("1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).claim("role", "CUSTOMER").build();
        String token = config.jwtEncoder(key).encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS384).build(), claims)).getTokenValue();
        assertThatThrownBy(() -> config.jwtDecoder(key, properties).decode(token)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not base64!"})
    void rejectsMissingOrMalformedSigningSecret(String value) {
        var invalid = new JwtProperties(value, properties.issuer(), properties.audience(), Duration.ofMinutes(15));
        assertThatThrownBy(() -> config.jwtSigningKey(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWeakSigningKeyWithoutIncludingItsValueInError() {
        String weak = Base64.getEncoder().encodeToString(new byte[16]);
        var invalid = new JwtProperties(weak, properties.issuer(), properties.audience(), Duration.ofMinutes(15));
        assertThatThrownBy(() -> config.jwtSigningKey(invalid)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(weak);
    }
}
