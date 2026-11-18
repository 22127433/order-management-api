package com.example.ordermanagement.auth;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(@Schema(description = "Signed access token; supply as Authorization: Bearer <token>") String accessToken,
                            @Schema(example = "Bearer") String tokenType,
                            @Schema(example = "900", description = "Token lifetime in seconds") long expiresIn,
                            UserResponse user) {
    @Override public String toString() { return "TokenResponse[accessToken=[PROTECTED], user=" + user + "]"; }
}
