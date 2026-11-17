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
class ApiDocumentationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private TestRestTemplate http;

    @Test
    void specificationDocumentsAllBusinessOperationsAndResolvesEveryLocalReference() {
        JsonNode spec = specification();
        assertThat(spec.path("openapi").asText()).startsWith("3.0.");
        assertThat(spec.at("/info/title").asText()).isEqualTo("Order Management API");
        for (String path : List.of("/api/products", "/api/products/{id}", "/api/products/{id}/restock",
                "/api/products/{id}/stock-movements", "/api/orders", "/api/orders/{id}",
                "/api/orders/{id}/confirm", "/api/orders/{id}/ship", "/api/orders/{id}/deliver", "/api/orders/{id}/cancel")) {
            assertThat(spec.path("paths").has(path)).as(path).isTrue();
        }
        assertThat(spec.path("paths").size()).isEqualTo(10);
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
        assertThat(operations).isEqualTo(13);
        assertReferencesResolve(spec, spec);
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
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "configprops", "beans", "metrics", "info", "heapdump", "loggers"})
    void otherActuatorEndpointsAreNotExposed(String endpoint) {
        assertThat(http.getForEntity("/actuator/" + endpoint, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
