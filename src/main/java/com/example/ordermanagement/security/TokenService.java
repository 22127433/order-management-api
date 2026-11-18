package com.example.ordermanagement.security;

import com.example.ordermanagement.auth.AppUser;
import com.example.ordermanagement.auth.TokenResponse;
import com.example.ordermanagement.auth.UserResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final JwtProperties properties;
    public TokenService(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }
    public TokenResponse issue(AppUser user) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience()))
                .subject(user.getId().toString()).issuedAt(now).expiresAt(now.plus(properties.accessTokenTtl()))
                .claim("role", user.getRole().name()).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", properties.accessTokenTtl().toSeconds(), UserResponse.from(user));
    }
}
