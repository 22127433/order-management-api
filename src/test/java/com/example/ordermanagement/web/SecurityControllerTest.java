package com.example.ordermanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermanagement.auth.AuthController;
import com.example.ordermanagement.auth.AuthService;
import com.example.ordermanagement.auth.UserRepository;
import com.example.ordermanagement.common.PageResponse;
import com.example.ordermanagement.order.IdempotentOrderService;
import com.example.ordermanagement.order.OrderController;
import com.example.ordermanagement.order.OrderService;
import com.example.ordermanagement.product.ProductController;
import com.example.ordermanagement.product.ProductService;
import com.example.ordermanagement.security.SecurityConfig;
import com.example.ordermanagement.support.TestSecurity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AuthController.class, ProductController.class, OrderController.class})
@Import(SecurityConfig.class)
class SecurityControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private UserRepository users;
    @MockitoBean private AuthService auth;
    @MockitoBean private ProductService products;
    @MockitoBean private OrderService orders;
    @MockitoBean private IdempotentOrderService idempotent;

    @ParameterizedTest
    @CsvSource({"POST,/api/products", "PUT,/api/products/1", "DELETE,/api/products/1",
            "POST,/api/products/1/restock", "GET,/api/products/1/stock-movements", "POST,/api/orders",
            "GET,/api/orders/1", "POST,/api/orders/1/cancel", "POST,/api/orders/1/confirm"})
    void unauthenticatedProtectedRequestsReturn401BeforeBusinessLogic(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.path").value(path));
        verifyNoInteractions(products, orders, idempotent);
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/products", "PUT,/api/products/1", "DELETE,/api/products/1",
            "POST,/api/products/1/restock", "GET,/api/products/1/stock-movements",
            "POST,/api/orders/1/cancel", "POST,/api/orders/1/confirm", "POST,/api/orders/1/ship", "POST,/api/orders/1/deliver"})
    void customerCannotPerformAdministrativeOperations(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).with(jwt().jwt(j -> j.subject("1"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(products, orders, idempotent);
    }

    @Test
    void authenticationDatabaseFailureIs500RatherThanMisleading401() throws Exception {
        when(decoder.decode("test-token")).thenReturn(org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token")
                .header("alg", "HS256").subject("1").claim("role", "CUSTOMER").issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(900)).build());
        when(users.findById(1L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Private database details"));
        var response = mvc.perform(get("/api/orders/1").header("Authorization", "Bearer test-token"))
                .andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("Private database details");
    }

    @Test
    void loginBackendFailureIs500AndDoesNotRevealDetails() throws Exception {
        when(auth.login(any())).thenThrow(new org.springframework.security.authentication.InternalAuthenticationServiceException("Private database details"));
        var response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\",\"password\":\"" + TestSecurity.PASSWORD + "\"}"))
                .andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("Private database details");
    }

    @Test
    void catalogReadRemainsPublic() throws Exception {
        when(products.findAll(any())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));
        mvc.perform(get("/api/products")).andExpect(status().isOk());
    }

    @Test
    void invalidBearerTokenReturnsGeneric401WithoutLeakingToken() throws Exception {
        String invalid = "test-invalid-token";
        when(decoder.decode(invalid)).thenThrow(new BadJwtException("Internal decoder details"));
        var response = mvc.perform(get("/api/orders/1").header("Authorization", "Bearer " + invalid))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(invalid, "Internal decoder details");
    }

    @Test
    void registrationRejectsClientSelectedAdminRoleBeforeService() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\",\"password\":\"" + TestSecurity.PASSWORD + "\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(auth);
    }

    @Test
    void loginFailuresHaveSameGenericMessageAnd401Status() throws Exception {
        when(auth.login(any())).thenThrow(new BadCredentialsException("Whether user exists must not be disclosed"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer@example.com\",\"password\":\"" + TestSecurity.PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void malformedRegistrationIsValidatedWithoutAuthentication() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"invalid\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors").isNotEmpty());
        verifyNoInteractions(auth);
    }
}
