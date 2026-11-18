package com.example.ordermanagement.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

@Schema(description = "Creates CUSTOMER only. Unknown fields, including role and userId, are rejected.")
public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) @Schema(example = "customer@example.com") String email,
        @NotBlank @Size(min = 12, max = 72)
        @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                description = "12–72 characters AND at most 72 UTF-8 bytes. Preserved exactly; never trimmed.") String password) {
    public RegisterRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
    @Override public String toString() { return "RegisterRequest[email=" + email + ", password=[PROTECTED]]"; }
}
