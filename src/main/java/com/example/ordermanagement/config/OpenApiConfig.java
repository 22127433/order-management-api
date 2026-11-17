package com.example.ordermanagement.config;

import com.example.ordermanagement.common.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {
    @Bean
    OpenAPI orderManagementOpenApi() {
        OpenAPI api = new OpenAPI()
                .info(new Info().title("Order Management API").version("1.0.0")
                        .description("Product catalog, order lifecycle, and transactional inventory. "
                                + "No authentication is implemented. Prices and totals are calculated by the server. "
                                + "Use a client-generated Idempotency-Key when retrying order creation. "
                                + "Demo instructions: README.md and scripts/demo.ps1 in the repository."))
                .tags(List.of(new Tag().name("Products").description("Catalog CRUD and database-side search/filtering"),
                        new Tag().name("Orders").description("Creation, idempotent retries, and lifecycle actions"),
                        new Tag().name("Inventory").description("Manual restock and immutable inventory movement history")))
                .components(new Components()
                        .addResponses("BadRequest", error("Invalid input, JSON, header, or query parameters", 400,
                                "Request validation failed", List.of(Map.of("field", "items[0].quantity",
                                        "message", "must be greater than 0"))))
                        .addResponses("NotFound", error("The requested product or order does not exist", 404,
                                "Order with id 999 was not found", List.of()))
                        .addResponses("Conflict", error("Business rule, inventory, idempotency, or lock conflict; "
                                        + "retry lock conflicts using the same key and payload", 409,
                                "Resource is busy; retry the request", List.of()))
                        .addResponses("InternalError", error("Unexpected failure; internal details are not returned", 500,
                                "An unexpected error occurred", List.of())));
        ModelConverters.getInstance().readAll(ApiError.class).forEach(api.getComponents()::addSchemas);
        return api;
    }

    private ApiResponse error(String description, int status, String message, List<Map<String, String>> fields) {
        return new ApiResponse().description(description).content(new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError"))
                        .addExamples("error", new Example().value(Map.of(
                                "timestamp", "2026-11-17T10:00:00Z", "status", status,
                                "error", switch (status) {
                                    case 400 -> "Bad Request";
                                    case 404 -> "Not Found";
                                    case 409 -> "Conflict";
                                    default -> "Internal Server Error";
                                }, "message", message, "path", status == 404 ? "/api/orders/999" : "/api/orders",
                                "fieldErrors", fields)))));
    }
}
