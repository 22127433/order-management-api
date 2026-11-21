package com.example.ordermanagement.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiDocumentationIT extends com.example.ordermanagement.support.JwtTestProperties {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private TestRestTemplate http;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void openApiDocumentsBearerSecurityAndExplicitPublicOperationsWithoutPasswordResponses() {
        JsonNode spec = specification();
        assertThat(spec.at("/components/securitySchemes/bearerAuth/type").asText()).isEqualTo("http");
        assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(spec.path("security").get(0).has("bearerAuth")).isTrue();
        for (String path : List.of("/api/auth/register", "/api/auth/login", "/api/products", "/api/products/{id}")) {
            String method = path.startsWith("/api/auth") ? "post" : "get";
            JsonNode operation = spec.path("paths").path(path).path(method);
            assertThat(operation.has("security")).as(path).isTrue();
            assertThat(operation.path("security").isEmpty()).as(path).isTrue();
        }
        assertThat(spec.at("/components/schemas/RegisterRequest/properties").has("role")).isFalse();
        assertThat(spec.at("/components/schemas/RegisterRequest/properties/password/writeOnly").asBoolean()).isTrue();
        assertThat(spec.at("/components/schemas/UserResponse/properties").has("passwordHash")).isFalse();
        assertThat(spec.at("/components/schemas/UserResponse/properties").has("password")).isFalse();
    }

    @Test
    void specificationDocumentsAllBusinessOperationsAndResolvesEveryLocalReference() {
        JsonNode spec = specification();
        assertThat(spec.path("openapi").asText()).startsWith("3.0.");
        assertThat(spec.at("/info/title").asText()).isEqualTo("Order Management API");
        for (String path : List.of("/api/auth/register", "/api/auth/login", "/api/products", "/api/products/{id}", "/api/products/{id}/restock",
                "/api/products/{id}/stock-movements", "/api/products/{id}/adjust-stock", "/api/products/{id}/availability", "/api/admin/products", "/api/orders", "/api/orders/{id}",
                "/api/orders/{id}/confirm", "/api/orders/{id}/ship", "/api/orders/{id}/deliver", "/api/orders/{id}/cancel")) {
            assertThat(spec.path("paths").has(path)).as(path).isTrue();
        }
        assertThat(spec.path("paths").size()).isEqualTo(15);
        int operations = 0;
        for (JsonNode path : spec.path("paths")) {
            for (JsonNode operation : path) {
                operations++;
                assertThat(operation.path("summary").asText()).isNotBlank();
                assertThat(operation.path("tags").isEmpty()).isFalse();
                for (String code : List.of("400", "500")) {
                    assertThat(operation.path("responses").has(code)).as(code).isTrue();
                }
            }
        }
        assertThat(operations).isEqualTo(19);
        assertReferencesResolve(spec, spec);
    }

    @Test
    void availabilityAndAdminCatalogDocumentValidationSecurityAndPublicVisibility() {
        JsonNode spec = specification();
        JsonNode availability = spec.path("paths").path("/api/products/{id}/availability").path("patch");
        assertThat(availability.path("description").asText()).contains("ADMIN only", "no stock change", "409");
        assertThat(availability.has("security")).isFalse();
        for (String code : List.of("200", "400", "401", "403", "404", "409", "500")) {
            assertThat(availability.path("responses").has(code)).as(code).isTrue();
        }
        JsonNode request = spec.at("/components/schemas/ProductAvailabilityRequest");
        assertThat(request.path("required").toString()).contains("active");
        assertThat(request.at("/properties/active/type").asText()).isEqualTo("boolean");
        assertThat(spec.at("/components/schemas/ProductResponse/properties/active/type").asText()).isEqualTo("boolean");
        JsonNode admin = spec.path("paths").path("/api/admin/products").path("get");
        assertThat(admin.path("description").asText()).contains("ADMIN only", "false selects inactive");
        assertThat(admin.has("security")).isFalse();
        assertThat(spec.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(parameter(admin, "active").path("required").asBoolean()).isFalse();
        assertThat(parameter(admin, "size").at("/schema/maximum").asInt()).isEqualTo(100);
        assertThat(spec.path("paths").path("/api/products").path("get").path("description").asText()).contains("active products only");
        assertThat(spec.path("paths").path("/api/products/{id}").path("get").path("description").asText()).contains("404");
    }

    @Test
    void orderListingDocumentsFiltersSummaryPaginationAndInheritedBearerSecurity() {
        JsonNode spec = specification();
        JsonNode listing = spec.path("paths").path("/api/orders").path("get");
        assertThat(listing.path("summary").asText()).isEqualTo("List orders");
        assertThat(listing.path("description").asText()).contains("CUSTOMER", "ADMIN", "createdAt DESC, id DESC");
        assertThat(listing.has("security")).isFalse(); // Global bearer requirement is inherited, never overridden with [].
        assertThat(spec.path("security").get(0).has("bearerAuth")).isTrue();
        assertThat(listing.path("parameters").size()).isEqualTo(5);
        for (String name : List.of("status", "from", "to", "page", "size")) {
            assertThat(parameter(listing, name).path("in").asText()).isEqualTo("query");
        }
        assertThat(parameter(listing, "from").at("/schema/format").asText()).isEqualTo("date-time");
        assertThat(parameter(listing, "to").at("/schema/format").asText()).isEqualTo("date-time");
        assertThat(parameter(listing, "page").at("/schema/default").asInt()).isZero();
        assertThat(parameter(listing, "size").at("/schema/default").asInt()).isEqualTo(20);
        assertThat(parameter(listing, "size").at("/schema/maximum").asInt()).isEqualTo(100);
        for (String code : List.of("200", "400", "401", "403", "500")) {
            assertThat(listing.path("responses").has(code)).as(code).isTrue();
        }
        JsonNode summary = spec.at("/components/schemas/OrderSummaryResponse/properties");
        assertThat(summary.size()).isEqualTo(4);
        assertThat(summary.has("items")).isFalse();
        assertThat(summary.has("userId")).isFalse();
    }

    @Test
    void creationDocumentsOptionalKeyReplayHeadersAndDistinctRequestResponseItems() {
        JsonNode spec = specification();
        JsonNode create = spec.path("paths").path("/api/orders").path("post");
        for (String code : List.of("201", "200", "400", "404", "409", "500")) {
            assertThat(create.path("responses").has(code)).as(code).isTrue();
        }
        JsonNode key = parameter(create, "Idempotency-Key");
        assertThat(key.path("in").asText()).isEqualTo("header");
        assertThat(key.path("required").asBoolean()).isFalse();
        assertThat(key.at("/schema/maxLength").asInt()).isEqualTo(128);
        assertThat(key.at("/schema/pattern").asText()).isEqualTo("^[A-Za-z0-9._:-]{1,128}$");
        for (String code : List.of("200", "201")) {
            assertThat(create.path("responses").path(code).path("headers").has("Location")).isTrue();
            assertThat(create.path("responses").path(code).path("headers").has("Idempotency-Replayed")).isTrue();
            assertThat(create.path("responses").path(code).at("/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/OrderResponse");
        }
        JsonNode schemas = spec.at("/components/schemas");
        assertThat(schemas.path("CreateOrderRequest").at("/properties/items/items/$ref").asText())
                .isEqualTo("#/components/schemas/CreateOrderItem");
        assertThat(schemas.path("OrderResponse").at("/properties/items/items/$ref").asText())
                .isEqualTo("#/components/schemas/OrderItemResponse");
        assertThat(schemas.path("CreateOrderItem").path("properties").has("unitPrice")).isFalse();
        assertThat(schemas.path("OrderItemResponse").path("properties").has("unitPrice")).isTrue();
    }

    @Test
    void metadataPutHasDedicatedSchemaWithoutStockAndCreationStillRequiresInitialStock() {
        JsonNode spec = specification();
        JsonNode create = spec.path("paths").path("/api/products").path("post");
        JsonNode update = spec.path("paths").path("/api/products/{id}").path("put");
        String schemaPath = "/requestBody/content/application~1json/schema/$ref";
        assertThat(create.at(schemaPath).asText()).isEqualTo("#/components/schemas/ProductRequest");
        assertThat(update.at(schemaPath).asText()).isEqualTo("#/components/schemas/UpdateProductRequest");
        JsonNode schemas = spec.at("/components/schemas");
        assertThat(schemas.path("ProductRequest").path("required").toString()).contains("stockQuantity");
        JsonNode metadata = schemas.path("UpdateProductRequest");
        assertThat(metadata.path("properties").size()).isEqualTo(3);
        assertThat(metadata.path("properties").has("stockQuantity")).isFalse();
        assertThat(metadata.path("properties").has("active")).isFalse();
        assertThat(metadata.path("required").toString()).contains("name", "price").doesNotContain("stockQuantity");
        assertThat(metadata.at("/properties/name/maxLength").asInt()).isEqualTo(120);
        assertThat(metadata.at("/properties/description/maxLength").asInt()).isEqualTo(2000);
        assertThat(metadata.at("/properties/price/minimum").decimalValue()).isEqualByComparingTo("0.01");
        assertThat(update.path("description").asText()).contains("unknown fields", "400", "stock and availability are preserved");
    }

    @Test
    void documentationIncludesValidationPaginationLifecycleAndSharedErrorSchemas() {
        JsonNode spec = specification();
        JsonNode schemas = spec.at("/components/schemas");
        assertThat(schemas.path("ProductRequest").at("/properties/name/maxLength").asInt()).isEqualTo(120);
        assertThat(schemas.path("ProductRequest").at("/properties/price/minimum").decimalValue()).isEqualByComparingTo("0.01");
        assertThat(schemas.path("CreateOrderRequest").at("/properties/items/maxItems").asInt()).isEqualTo(100);
        assertThat(schemas.path("CreateOrderItem").at("/properties/quantity/maximum").asInt()).isEqualTo(1_000_000);
        assertThat(schemas.path("RestockRequest").at("/properties/reason/maxLength").asInt()).isEqualTo(500);
        JsonNode search = spec.path("paths").path("/api/products").path("get");
        for (String field : List.of("keyword", "minPrice", "maxPrice", "inStock", "page", "size", "sort")) {
            assertThat(parameter(search, field).path("in").asText()).isEqualTo("query");
        }
        assertThat(parameter(search, "size").at("/schema/maximum").asInt()).isEqualTo(100);
        assertThat(parameter(search, "page").at("/schema/default").asInt()).isZero();
        assertThat(parameter(search, "sort").path("description").asText()).contains("stockQuantity");
        for (String action : List.of("confirm", "ship", "deliver", "cancel")) {
            JsonNode operation = spec.path("paths").path("/api/orders/{id}/" + action).path("post");
            assertThat(operation.path("responses").has("409")).isTrue();
            assertThat(operation.path("description").asText()).contains("Only ");
        }
        assertThat(schemas.path("ApiError").path("properties").has("fieldErrors")).isTrue();
        assertThat(spec.at("/components/responses/BadRequest/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ApiError");
        assertThat(spec.path("paths").path("/api/products/{id}").path("delete").path("responses").has("204")).isTrue();
        assertThat(spec.path("paths").path("/api/products/{id}").path("delete").at("/responses/204").has("content")).isFalse();
    }

    @Test
    void swaggerUiHtmlAssetsAndConfigurationAreServedOverHttp() {
        var page = http.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("Swagger UI", "swagger-ui-bundle.js");
        var script = http.getForEntity("/swagger-ui/swagger-ui-bundle.js", String.class);
        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(script.getBody()).contains("SwaggerUIBundle");
        JsonNode config = http.getForObject("/v3/api-docs/swagger-config", JsonNode.class);
        assertThat(config.path("url").asText()).isEqualTo("/v3/api-docs");
        assertThat(config.toString()).doesNotContain("petstore.swagger.io");
    }

    @Test
    void healthExposesOnlyStatusAndApplicationDoesNotSeedDemoData() {
        var health = http.getForEntity("/actuator/health", JsonNode.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().size()).isEqualTo(1);
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
        JsonNode products = http.getForObject("/api/products", JsonNode.class);
        assertThat(products.path("totalElements").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_users", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "configprops", "beans", "metrics", "info", "heapdump", "loggers"})
    void otherActuatorEndpointsAreNotExposed(String endpoint) {
        assertThat(http.getForEntity("/actuator/" + endpoint, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void stockAdjustmentDocumentsSignedDeltaBoundsReasonErrorsAndBearerSecurity() {
        JsonNode spec = specification();
        JsonNode adjustment = spec.path("paths").path("/api/products/{id}/adjust-stock").path("post");
        assertThat(adjustment.path("description").asText()).contains("ADMIN only", "nonzero", "MANUAL_ADJUSTMENT", "long arithmetic", "409", "repeating");
        assertThat(adjustment.has("security")).isFalse();
        for (String code : List.of("200", "400", "401", "403", "404", "409", "500")) {
            assertThat(adjustment.path("responses").has(code)).as(code).isTrue();
        }
        JsonNode request = spec.at("/components/schemas/AdjustStockRequest");
        assertThat(request.path("properties").size()).isEqualTo(2);
        assertThat(request.path("required").toString()).contains("delta", "reason");
        assertThat(request.at("/properties/delta/minimum").asLong()).isEqualTo(Integer.MIN_VALUE);
        assertThat(request.at("/properties/delta/maximum").asLong()).isEqualTo(Integer.MAX_VALUE);
        assertThat(request.at("/properties/delta/description").asText()).contains("zero is invalid");
        assertThat(request.at("/properties/reason/maxLength").asInt()).isEqualTo(500);
        assertThat(spec.at("/components/schemas/StockMovementResponse/properties/type/enum").toString())
                .contains("MANUAL_ADJUSTMENT", "MANUAL_RESTOCK", "INITIAL_STOCK", "ORDER_DEDUCTION", "ORDER_CANCELLATION");
        assertThat(spec.at("/components/schemas/StockMovementResponse/properties/reason/description").asText())
                .contains("manual restock", "manual adjustment");
    }

    @Test
    void everyEndpointDocumentsItsPermissionSuccessStatusAndActualRequestResponseDtos() {
        JsonNode spec = specification();
        // method, path, success, request DTO (or -), response DTO (or -), role, paged
        String contracts = """
                post /api/auth/register 201 RegisterRequest UserResponse PUBLIC false
                post /api/auth/login 200 LoginRequest TokenResponse PUBLIC false
                post /api/products 201 ProductRequest ProductResponse ADMIN false
                get /api/products 200 - ProductResponse PUBLIC true
                get /api/products/{id} 200 - ProductResponse PUBLIC false
                put /api/products/{id} 200 UpdateProductRequest ProductResponse ADMIN false
                delete /api/products/{id} 204 - - ADMIN false
                patch /api/products/{id}/availability 200 ProductAvailabilityRequest ProductResponse ADMIN false
                get /api/admin/products 200 - ProductResponse ADMIN true
                post /api/products/{id}/restock 200 RestockRequest ProductResponse ADMIN false
                post /api/products/{id}/adjust-stock 200 AdjustStockRequest ProductResponse ADMIN false
                get /api/products/{id}/stock-movements 200 - StockMovementResponse ADMIN true
                post /api/orders 201 CreateOrderRequest OrderResponse CUSTOMER false
                get /api/orders 200 - OrderSummaryResponse CUSTOMER true
                get /api/orders/{id} 200 - OrderResponse CUSTOMER false
                post /api/orders/{id}/confirm 200 - OrderResponse ADMIN false
                post /api/orders/{id}/ship 200 - OrderResponse ADMIN false
                post /api/orders/{id}/deliver 200 - OrderResponse ADMIN false
                post /api/orders/{id}/cancel 200 - OrderResponse ADMIN false
                """;
        for (String line : contracts.strip().split("\\R")) {
            String[] contract = line.strip().split(" ");
            JsonNode operation = spec.path("paths").path(contract[1]).path(contract[0]);
            boolean publicRoute = contract[5].equals("PUBLIC");
            if (publicRoute) {
                assertThat(operation.has("security")).as(line).isTrue();
                assertThat(operation.path("security").isEmpty()).as(line).isTrue();
            } else {
                assertThat(operation.has("security")).as(line).isFalse(); // Inherit global bearerAuth.
                assertThat(operation.path("description").asText()).as(line).contains(contract[5]);
                assertThat(operation.path("responses").has("401")).as(line).isTrue();
                assertThat(operation.path("responses").has("403")).as(line).isTrue();
            }
            if (!contract[3].equals("-")) {
                assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText()).as(line)
                        .isEqualTo("#/components/schemas/" + contract[3]);
            } else {
                assertThat(operation.has("requestBody")).as(line).isFalse();
            }
            JsonNode response = operation.path("responses").path(contract[2]);
            assertThat(response.isMissingNode()).as(line).isFalse();
            if (contract[4].equals("-")) {
                assertThat(response.has("content")).as(line).isFalse();
            } else {
                JsonNode schema = response.at("/content/application~1json/schema");
                if (Boolean.parseBoolean(contract[6])) {
                    schema = spec.at(schema.path("$ref").asText().substring(1)).at("/properties/content/items");
                }
                assertThat(schema.path("$ref").asText()).as(line).isEqualTo("#/components/schemas/" + contract[4]);
            }
        }
    }

    private JsonNode specification() {
        var response = http.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (parameter.path("name").asText().equals(name)) { return parameter; }
        }
        throw new AssertionError("Missing documented parameter: " + name);
    }

    private void assertReferencesResolve(JsonNode root, JsonNode node) {
        if (node.isObject() && node.has("$ref")) {
            String ref = node.path("$ref").asText();
            assertThat(ref).startsWith("#/");
            assertThat(root.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();
        }
        for (JsonNode child : node) { assertReferencesResolve(root, child); }
    }
}
