package com.example.ordermanagement.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
@SecurityRequirements
@ApiResponses({@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequest"),
        @ApiResponse(responseCode = "500", ref = "#/components/responses/InternalError")})
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register")
    @Operation(summary = "Register a CUSTOMER", description = "Role is assigned by the server. Email is trimmed/lowercased. "
            + "Password is BCrypt-hashed; no password/hash is returned. No token is issued until login.")
    @ApiResponse(responseCode = "201", description = "Customer registered", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(auth.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in and obtain an access token", description = "Invalid credentials return the same generic 401. "
            + "No session cookie or refresh token is issued.")
    @ApiResponse(responseCode = "200", description = "Bearer access token", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(auth.login(request));
    }
}
