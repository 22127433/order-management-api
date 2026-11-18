package com.example.ordermanagement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordermanagement.common.PageResponse;
import com.example.ordermanagement.product.ProductRequest;
import com.example.ordermanagement.product.ProductResponse;
import com.example.ordermanagement.product.ProductSearchQuery;
import com.example.ordermanagement.product.ProductService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(ProductSearchIT.SqlCaptureConfiguration.class)
class ProductSearchIT extends com.example.ordermanagement.support.AuthenticatedIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final ThreadLocal<List<String>> SQL = ThreadLocal.withInitial(ArrayList::new);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCaptureConfiguration {
        @Bean
        HibernatePropertiesCustomizer captureSearchSql() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector",
                    (StatementInspector) sql -> {
                        SQL.get().add(sql);
                        return sql;
                    });
        }
    }

    @Autowired private ProductService service;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE order_items, customer_orders, products RESTART IDENTITY CASCADE");
        SQL.get().clear();
    }

    @Test
    void searchesNameCaseInsensitivelyAndCombinesEveryFilter() throws Exception {
        product("Mechanical Keyboard", "20.00", 3);
        product("KEYBOARD Pro", "40.00", 5);
        product("Keyboard sold out", "30.00", 0);
        product("Keyboard premium", "60.00", 2);
        service.create(new ProductRequest("Mouse", "keyboard in description only", new BigDecimal("30.00"), 2));

        mvc.perform(get("/api/products").param("keyword", "  keyBOARD  ")
                        .param("minPrice", "20").param("maxPrice", "40.00").param("inStock", "true")
                        .param("sort", "price,desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].name").value("KEYBOARD Pro"))
                .andExpect(jsonPath("$.content[1].name").value("Mechanical Keyboard"));
    }

    @Test
    void appliesEachPriceBoundIndependentlyAndIncludesExactDecimalBoundaries() {
        product("Cheap", "0.10", 1);
        product("Boundary", "19.99", 1);
        product("High", "20.00", 1);

        assertThat(search(null, "19.99", null, null).content()).extracting(ProductResponse::name)
                .containsExactly("Boundary", "High");
        assertThat(search(null, null, "19.99", null).content()).extracting(ProductResponse::name)
                .containsExactly("Cheap", "Boundary");
        assertThat(search(null, "19.990", "19.99", null).content()).extracting(ProductResponse::name)
                .containsExactly("Boundary");
        assertThat(search(null, "0", "0.10", null).content()).extracting(ProductResponse::name)
                .containsExactly("Cheap");
    }

    @Test
    void distinguishesAvailableUnavailableAndOmittedStockFilter() {
        product("Available", "10.00", 1);
        product("Unavailable", "10.00", 0);
        assertThat(search(null, null, null, true).content()).extracting(ProductResponse::name)
                .containsExactly("Available");
        assertThat(search(null, null, null, false).content()).extracting(ProductResponse::name)
                .containsExactly("Unavailable");
        assertThat(search(null, null, null, null).totalElements()).isEqualTo(2);
    }

    @Test
    void missingAndBlankFiltersPreserveOldDefaults() throws Exception {
        product("Z last alphabetically", "10.00", 0);
        product("A first alphabetically", "5.00", 2);
        mvc.perform(get("/api/products"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[1].id").value(2));
        assertThat(search("   ", null, null, null).totalElements()).isEqualTo(2);
        assertThat(service.findAll(0, 1).content()).extracting(ProductResponse::id).containsExactly(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!", "\\", "' OR 1=1 --"})
    void keywordIsLiteralIncludingLikeMetacharactersAndSqlSyntax(String keyword) {
        product("Literal " + keyword + " match", "10.00", 1);
        product("Unrelated product", "10.00", 1);
        assertThat(search(keyword, null, null, null).content()).extracting(ProductResponse::name)
                .containsExactly("Literal " + keyword + " match");
    }

    @Test
    void filtersBeforePagingAndCountsOnlyMatchingRowsWithStableTies() throws Exception {
        product("Other", "10.00", 1);
        product("Match A", "20.00", 1);
        product("Match B", "20.00", 1);
        product("Match C", "20.00", 1);
        product("Match D", "30.00", 1);
        mvc.perform(get("/api/products").param("keyword", "match").param("sort", "price,asc")
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Match C"))
                .andExpect(jsonPath("$.content[1].name").value("Match D"))
                .andExpect(jsonPath("$.totalElements").value(4)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.last").value(true));
        assertThat(SQL.get()).anySatisfy(sql -> assertThat(sql.toLowerCase())
                .contains("lower(", " like ", "order by", "offset", "fetch first"));
        assertThat(SQL.get()).anySatisfy(sql -> assertThat(sql.toLowerCase()).contains("count(", " like "));
    }

    @Test
    void unmatchedAndOutOfRangePagesAreEmptyButKeepCorrectTotals() throws Exception {
        product("Keyboard", "10.00", 1);
        mvc.perform(get("/api/products").param("keyword", "not found"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
        mvc.perform(get("/api/products").param("keyword", "keyboard").param("page", "5").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.totalPages").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "name", "price", "stockQuantity"})
    void everyAllowedSortExecutesAgainstPostgresqlInBothDirections(String field) {
        product("Z", "30.00", 3);
        product("A", "10.00", 1);
        String expectedAscending = field.equals("id") ? "Z" : "A";
        String expectedDescending = field.equals("id") ? "A" : "Z";
        assertThat(service.findAll(new ProductSearchQuery(null, null, null, null, 0, 20, field)).content())
                .extracting(ProductResponse::name).containsExactly(expectedAscending, expectedDescending);
        assertThat(service.findAll(new ProductSearchQuery(null, null, null, null, 0, 20, field + ",desc")).content())
                .extracting(ProductResponse::name).containsExactly(expectedDescending, expectedAscending);
    }

    @Test
    void invalidFiltersReturn400WithRealServiceAndDatabase() throws Exception {
        for (String query : List.of("minPrice=-1", "minPrice=20&maxPrice=10", "size=101", "sort=description")) {
            mvc.perform(get("/api/products?" + query)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void selectivePriceQueryUsesMigratedIndexAndDatabaseLimitOnRepresentativeData() throws Exception {
        jdbc.execute("""
                INSERT INTO products(name, price, stock_quantity)
                SELECT 'Product ' || n, n::numeric / 100, CASE WHEN n % 5 = 0 THEN 0 ELSE 10 END
                FROM generate_series(1, 20000) AS n
                """);
        jdbc.execute("ANALYZE products");
        SQL.get().clear();
        var result = service.findAll(new ProductSearchQuery(null, new BigDecimal("100.00"),
                new BigDecimal("100.09"), null, 0, 2, "price,asc"));
        assertThat(result.content()).hasSize(2);
        assertThat(result.totalElements()).isEqualTo(10);
        assertThat(result.content()).extracting(ProductResponse::price)
                .containsExactly(new BigDecimal("100.00"), new BigDecimal("100.01"));
        String generatedSelect = SQL.get().stream().filter(sql -> sql.contains("order by")).findFirst().orElseThrow();
        assertThat(generatedSelect).contains("fetch first");
        List<Object> parameters = new ArrayList<>(List.of(new BigDecimal("100.00"), new BigDecimal("100.09")));
        if (generatedSelect.contains("offset")) {
            parameters.add(0);
        }
        parameters.add(2);
        String pricePlan = String.join("\n", jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + generatedSelect,
                String.class, parameters.toArray()));
        assertThat(pricePlan).contains("idx_products_price_id");
        String keywordPlan = explain("SELECT * FROM products WHERE lower(name) LIKE '%100%' ESCAPE '!' ORDER BY id LIMIT 20");
        String stockPlan = explain("SELECT * FROM products WHERE stock_quantity > 0 ORDER BY id LIMIT 20");
        String countPlan = explain("SELECT count(*) FROM products WHERE price >= 100.00 AND price <= 100.09");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/product-search-explain.txt"),
                "Fixture: 20,000 generated products; ANALYZE products; not a throughput benchmark.\n\n"
                        + "Generated JPA SQL:\n" + generatedSelect + "\n\nPrice page:\n" + pricePlan
                        + "\n\nPrice count:\n" + countPlan + "\n\nKeyword:\n" + keywordPlan
                        + "\n\nIn stock:\n" + stockPlan + "\n");
    }

    private String explain(String sql) {
        return String.join("\n", jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class));
    }

    private ProductResponse product(String name, String price, int stock) {
        return service.create(new ProductRequest(name, null, new BigDecimal(price), stock));
    }

    private PageResponse<ProductResponse> search(
            String keyword, String min, String max, Boolean inStock) {
        return service.findAll(new ProductSearchQuery(keyword, min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max), inStock, 0, 20, null));
    }
}
