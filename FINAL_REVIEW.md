# Final Review — Order Management API

Review date: **21 November 2026**. Scope: the current workspace, including the previously implemented listing, availability, metadata and manual adjustment changes. This review keeps the monolith and its existing business rules.

## Findings and Changes

- Corrected the OpenAPI StockMovementResponse reason description: both manual restock and adjustment require a reason.
- Corrected the shared 409 documentation: only keyed order creation deduplicates retries; restock/adjustment must not be retried blindly after an uncertain success.
- Corrected README foreign-key wording: order items cascade with their parent, while movement/receipt references use RESTRICT. No order-deletion API exists.
- Extended OpenAPI contract verification to every business operation: permission description, bearer/public requirement, request/response DTO, page item type, success status and empty DELETE response.
- Added PostgreSQL migration verification for a clean installation and every V1–V6 baseline, with representative pre-existing data. No existing migration was changed.
- Reworked the PowerShell demo so the same keyed order follows creation/replay/confirmation/shipping/delivery. Added ownership-scoped listing, unique-name search, deactivation, signed adjustments, restock, key rollback/retry and cancellation of a second order.
- Updated the HTTP collection, README and Vietnamese interview guide. CI now checks Docker explicitly before running all Maven tests, including the new migration cases.

## Source Review and Executed Coverage

| Area | Verified behavior | Main source / evidence |
| --- | --- | --- |
| Security / JWT | BCrypt cost 12; 72-byte password guard; pinned HS256; issuer/audience/timestamps/subject/role validation; database identity freshness; no client ADMIN registration; 401/403 | [JwtConfig](src/main/java/com/example/ordermanagement/security/JwtConfig.java), [SecurityConfig](src/main/java/com/example/ordermanagement/security/SecurityConfig.java), [AuthService](src/main/java/com/example/ordermanagement/auth/AuthService.java), [AuthenticationAuthorizationIT](src/test/java/com/example/ordermanagement/integration/AuthenticationAuthorizationIT.java) |
| Ownership | Principal supplies owner; CUSTOMER reads only its orders; ADMIN includes legacy NULL owners; another customer's detail returns 404 | [OrderService](src/main/java/com/example/ordermanagement/order/OrderService.java), [OrderListingIT](src/test/java/com/example/ordermanagement/integration/OrderListingIT.java) |
| Lifecycle | CREATED -> CONFIRMED -> SHIPPED -> DELIVERED, or CREATED -> CANCELLED; state checks after order write lock; cancel once; fulfillment does not deduct again | [OrderStatus](src/main/java/com/example/ordermanagement/order/OrderStatus.java), [OrderManagementIT](src/test/java/com/example/ordermanagement/integration/OrderManagementIT.java) |
| Idempotency | PostgreSQL user/key uniqueness; original response replay 200 vs first 201; mismatched payload 409; competing claims serialize; failed transactions release the key | [IdempotentOrderService](src/main/java/com/example/ordermanagement/order/IdempotentOrderService.java), [OrderIdempotencyRepository](src/main/java/com/example/ordermanagement/order/OrderIdempotencyRepository.java), [OrderIdempotencyIT](src/test/java/com/example/ordermanagement/integration/OrderIdempotencyIT.java) |
| Catalog / availability | Database Specification search/count/page; literal case-insensitive keyword; inclusive prices; whitelisted sort; inactive hidden publicly and rejected after locking; ADMIN can reactivate | [ProductService](src/main/java/com/example/ordermanagement/product/ProductService.java), [ProductSearchIT](src/test/java/com/example/ordermanagement/integration/ProductSearchIT.java), [ProductAvailabilityIT](src/test/java/com/example/ordermanagement/integration/ProductAvailabilityIT.java) |
| Order listing | Ownership predicate in database; status/date filters; bounded page/offset; fixed createdAt/id descending; four-field projection without item queries | [OrderSpecifications](src/main/java/com/example/ordermanagement/order/OrderSpecifications.java), [OrderListQuery](src/main/java/com/example/ordermanagement/order/OrderListQuery.java), [OrderListingIT](src/test/java/com/example/ordermanagement/integration/OrderListingIT.java) |
| Inventory | Restock unchanged; signed nonzero adjustment; long bounds check; stock/journal atomicity; reason and snapshots retained; ADMIN-only; inactive stays inactive | [Product](src/main/java/com/example/ordermanagement/product/Product.java), [StockMovement](src/main/java/com/example/ordermanagement/inventory/StockMovement.java), [InventoryManagementIT](src/test/java/com/example/ordermanagement/integration/InventoryManagementIT.java) |
| Transactions / concurrency | Public service write transactions through Spring proxies; shared product write locks; ascending product IDs; cancellation locks order first; rollback checked after SQL failure; controlled real lock waits | [OrderService](src/main/java/com/example/ordermanagement/order/OrderService.java), [ProductService](src/main/java/com/example/ordermanagement/product/ProductService.java), [OrderManagementIT](src/test/java/com/example/ordermanagement/integration/OrderManagementIT.java), [InventoryManagementIT](src/test/java/com/example/ordermanagement/integration/InventoryManagementIT.java) |
| Money / metadata | BigDecimal totals and immutable original price snapshots; metadata-only PUT cannot supply stock; lock prevents stock overwrite during ordering | [OrderItem](src/main/java/com/example/ordermanagement/order/OrderItem.java), [UpdateProductRequest](src/main/java/com/example/ordermanagement/product/UpdateProductRequest.java), [OrderManagementIT](src/test/java/com/example/ordermanagement/integration/OrderManagementIT.java) |
| Errors / validation | Shared ApiError; input 400, auth 401, role 403, absent/not-owned 404, conflict 409, generic unexpected 500; no entity/password response | [GlobalExceptionHandler](src/main/java/com/example/ordermanagement/common/GlobalExceptionHandler.java), [SecurityErrorHandler](src/main/java/com/example/ordermanagement/security/SecurityErrorHandler.java), [MVC tests](src/test/java/com/example/ordermanagement/web/ApiControllerTest.java) |
| OpenAPI | All 19 operations on 15 paths, matching DTO/page contracts, documented permissions/statuses, resolvable schema references, Swagger assets served over HTTP | [ApiDocumentationIT](src/test/java/com/example/ordermanagement/integration/ApiDocumentationIT.java) |
| Migrations | Fresh V1–V8; each V1–V6 baseline upgraded to V8; product/order/item values, available journal/receipt data and existing ownership preserved; old products active; no invented history/owner/seed | [MigrationCompatibilityIT](src/test/java/com/example/ordermanagement/integration/MigrationCompatibilityIT.java), [Flyway migrations](src/main/resources/db/migration/V8__manual_stock_adjustments.sql) |

## Maven Results

Executed on Windows 11, Java 21.0.6, Maven 3.9.11 through Maven Wrapper, Docker 27.3.1, PostgreSQL 17.11 Testcontainers. Spring Boot version in pom.xml is 3.5.16. Both commands completed with BUILD SUCCESS:

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify -Pintegration
```

The local commands additionally used `-o`, `-B`, `-ntp`, `-Ddebug=false` and `-Dmaven.repo.local=D:\Document\order-management-api\.cache\repository` to reuse the available dependency cache.

| Run / group | Passed | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Standalone test: unit + MVC | 411 | 0 | 0 | 0 |
| verify: unit | 212 | 0 | 0 | 0 |
| verify: MVC | 199 | 0 | 0 | 0 |
| verify: PostgreSQL integration | 182 | 0 | 0 | 0 |
| **verify total (27 classes)** | **593** | **0** | **0** | **0** |

The standalone unit/MVC run is repeated by verify and is not added again to 593. Counts include parameterized invocations. XML evidence is in target/surefire-reports and target/failsafe-reports; local logs are .cache/portfolio-final-unit-mvc.log and .cache/portfolio-final-verification.log. Generated reports/logs are excluded from Git. No test failure required a business-code change in this review.

Mockito tests verify business branches and repository interactions. They do not establish database rollback or concurrency. PostgreSQL tests inject SQL failures and inspect committed state; controlled races confirm actual Lock waits in pg_stat_activity. These are representative schedules, not exhaustive concurrency verification or performance measurements.

## Docker Compose and Demo Results

The current Dockerfile built successfully and the optional app profile started both PostgreSQL and API with health checks. PostgreSQL-only Compose also started without JWT configuration. The guided demo completed twice, first on a fresh database and then on the populated database; each run created its own customer/products/orders and finished with keyboard stock **22**, active=true and **7 movements**.

The demo exercised register/login, product creation/search, metadata PUT, keyed creation/retry/conflict, CUSTOMER listing, ADMIN confirm/ship/deliver, deactivation/public404, inactive purchase rejection, preserved order/replay, adjustment -3/+2, restock+5, reactivation, failed-key retry, cancellation and rejection cases. The final chain was `+20,-2,-3,+5,+2,-1,+1` with consistent before/after values.

Headless Chrome rendered all **19** Swagger operations and Authorize. After recreating the API with bootstrap disabled and its password removed, ADMIN login and a pre-restart token still worked with the persisted order. Random test credentials and an isolated Compose project/volume were used; the temporary env file, containers and volume were removed afterward. Local evidence: .cache/portfolio-final-smoke.log and .cache/portfolio-smoke-result.json. Smoke runs are not included in the 593 JUnit cases.

## CI and Repository Hygiene

[GitHub Actions](.github/workflows/ci.yml) uses Java 21, checks Docker and runs the full integration profile on push/PR. The fresh/upgrade migration tests are discovered by Failsafe. The Unix wrapper is versioned executable with LF line endings; Windows uses mvnw.cmd. This review did not trigger a GitHub Actions run, commit or push.

Secrets come from ignored local environment configuration or runtime test generation. .env.example keeps password/key fields empty; bootstrap defaults false. .gitignore/.dockerignore exclude real env files, credentials, private keys, build artifacts and caches. The repository audit checked **117 release files and 7 historical commits**, found no matching secret patterns and no build/cache/IDE/real-env files in the release set. A pattern scan supports review but is not a penetration test or a guarantee against every secret format.

## Remaining Limitations and Risks

- No refresh/revocation/logout/password reset, role-management API, rate limiting or production HTTPS setup. HS256 key rotation invalidates all tokens; deployment needs operational secret management.
- Order receipts grow indefinitely. Restock/adjustment are not idempotent. There is no lease takeover for a live stalled transaction; lock_timeout bounds lock waits rather than total transaction lifetime. No universal exactly-once claim is made.
- CREATED orders reserve stock indefinitely until ADMIN action. Cancellation can conflict if intervening stock increases leave no INTEGER capacity for restoration; the transaction stays unchanged.
- Legacy inventory is not backfilled; old NULL owners stay ADMIN-only. Stock movements lack an actor-user field, availability changes lack history, and database operators can bypass application immutability.
- Metadata lacks ETag/@Version stale-edit detection. Large offsets, substring searches without pg_trgm and order sorting may become expensive. Additional indexes need representative query-plan evidence.
- No payment, tax/discount/currency model or carrier integration. Local tests do not establish throughput, uptime, penetration-test results or complete coverage of every race/schema drift.

## CV Bullets

- Built a Java 21 Spring Boot REST API with JWT authentication, BCrypt passwords, role authorization, and order ownership checks.
- Implemented order lifecycle management and PostgreSQL-backed idempotent creation with transactional inventory updates.
- Added database-side product/order filtering, pagination, product availability controls, and inventory movement history.
- Verified 593 JUnit 5, Mockito, MVC, and PostgreSQL Testcontainers cases, including rollback, concurrency, and Flyway upgrade scenarios.
