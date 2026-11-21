package com.example.ordermanagement.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermanagement.auth.UserRepository;
import com.example.ordermanagement.common.PageResponse;
import com.example.ordermanagement.common.ResourceNotFoundException;
import com.example.ordermanagement.product.AdminProductController;
import com.example.ordermanagement.product.ProductAvailabilityRequest;
import com.example.ordermanagement.product.ProductController;
import com.example.ordermanagement.product.ProductResponse;
import com.example.ordermanagement.product.ProductSearchQuery;
import com.example.ordermanagement.product.ProductService;
import com.example.ordermanagement.security.SecurityConfig;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({ProductController.class, AdminProductController.class})
@Import(SecurityConfig.class)
class ProductAvailabilityControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ProductService products;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private UserRepository users;

    @Test
    void adminCanSetAvailabilityAndReceivesUnchangedStock() throws Exception {
        when(products.changeAvailability(1L, new ProductAvailabilityRequest(false))).thenReturn(inactive());
        mvc.perform(patch("/api/products/1/availability").with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.stockQuantity").value(10));
        verify(products).changeAvailability(1L, new ProductAvailabilityRequest(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"active\":null}", "{\"active\":\"invalid\"}", "{\"active\":false,\"stockQuantity\":0}", "[]", ""})
    void invalidAvailabilityBodiesReturn400BeforeService(String body) throws Exception {
        mvc.perform(patch("/api/products/1/availability").with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(products);
    }

    @Test
    void missingProductAndInvalidIdKeepStandardErrors() throws Exception {
        when(products.changeAvailability(99L, new ProductAvailabilityRequest(false)))
                .thenThrow(new ResourceNotFoundException("Product", 99L));
        mvc.perform(patch("/api/products/99/availability").with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/products/0/availability").with(user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "AUDITOR"})
    void nonAdminsCannotChangeAvailabilityOrAccessAdminCatalog(String role) throws Exception {
        mvc.perform(patch("/api/products/1/availability").with(user("1").roles(role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/products").with(user("1").roles(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(products);
    }

    @Test
    void anonymousAdminEndpointsReturn401() throws Exception {
        mvc.perform(patch("/api/products/1/availability").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/products")).andExpect(status().isUnauthorized());
        verifyNoInteractions(products);
    }

    @Test
    void adminCatalogBindsActiveAndReusesSearchValidationAndPagination() throws Exception {
        var search = new ProductSearchQuery("Keyboard", new BigDecimal("1"), new BigDecimal("20"), true, 1, 2, "price,desc");
        when(products.findAllForAdmin(search, false)).thenReturn(new PageResponse<>(List.of(inactive()), 1, 2, 3, 2, true));
        mvc.perform(get("/api/admin/products").with(user("1").roles("ADMIN"))
                        .param("active", "false").param("keyword", " Keyboard ").param("minPrice", "1").param("maxPrice", "20")
                        .param("inStock", "true").param("page", "1").param("size", "2").param("sort", "price,desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].active").value(false))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.totalElements").value(3));
        verify(products).findAllForAdmin(search, false);
    }

    @Test
    void adminCatalogOmittedActiveIncludesBothAndPublicCannotChooseInactive() throws Exception {
        var search = new ProductSearchQuery(null, null, null, null, 0, 20, "id,asc");
        var empty = new PageResponse<ProductResponse>(List.of(), 0, 20, 0, 0, true);
        when(products.findAllForAdmin(search, null)).thenReturn(empty);
        when(products.findAll(search)).thenReturn(empty);
        mvc.perform(get("/api/admin/products").with(user("1").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/products").param("active", "false")).andExpect(status().isOk());
        verify(products).findAllForAdmin(search, null);
        verify(products).findAll(search);
    }

    @ParameterizedTest
    @ValueSource(strings = {"active=invalid", "size=101", "minPrice=20&maxPrice=1", "sort=active", "page=2147483647&size=100"})
    void adminCatalogRejectsInvalidSearchBeforeService(String query) throws Exception {
        mvc.perform(get("/api/admin/products?" + query).with(user("1").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(products);
    }

    private ProductResponse inactive() { return new ProductResponse(1L, "Keyboard", null, BigDecimal.TEN, 10, false); }
}
