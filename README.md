# Order Management API

A Java 21 / Spring Boot monolith demonstrating product search, transactional inventory, order lifecycle management, persistent idempotency, and user-based access control. It is a portfolio project for a Fresher Java Backend role.

[Overview](#project-overview) | [Stack](#tech-stack) | [Features](#features) | [Architecture](#architecture) | [Database](#database-schema-and-relationships) | [Endpoints](#api-endpoints) | [Run](#how-to-run) | [Examples](#example-requests--responses) | [Tests](#testing) | [Decisions](#design-decisions)

## Project Overview

Customers register, log in, browse products, and create/read their own orders. Administrators manage the catalog and inventory, inspect any order, and cancel or advance orders through fulfillment. Administrators may also create orders owned by themselves. Customer cancellation is not exposed: cancellation and all lifecycle actions require ADMIN.

Prices are read from the database and snapshotted when ordering. Order/items, stock changes, inventory movements, and any idempotency receipt commit or roll back together. The project remains one Spring Boot application and one PostgreSQL database. There is no frontend application, payment/carrier integration, microservice, Redis, Kafka, refresh-token flow, or user/role administration API.

[Swagger UI](http://localhost:8080/swagger-ui/index.html) is available after startup. [FINAL_REVIEW.md](FINAL_REVIEW.md) records the current verification and risks; [INTERVIEW_PREP.md](INTERVIEW_PREP.md) explains the source in Vietnamese. [REVIEW.md](REVIEW.md) is historical and predates the current security/API contract.

### Core business rules

| Area | Rule |
| --- | --- |
| Identity | Registration always creates CUSTOMER. Order owner comes from the authenticated principal, never a request userId. |
| Lifecycle | CREATED -> CONFIRMED -> SHIPPED -> DELIVERED, or CREATED -> CANCELLED. Only ADMIN performs transitions; repeated/invalid transitions return 409. |
| Inventory | Creation deducts stock once. Only a successful cancellation from CREATED restores it. Fulfillment never deducts again. Stock and movements share a transaction. |
| Pricing | Prices come from locked products and use BigDecimal. Existing order unit prices, subtotals and totals remain snapshots. |
| Availability | Inactive products are hidden from public reads and rejected after locking during new purchases. Existing orders/history remain accessible under their usual permissions. |
| Retries | Order Idempotency-Key is optional, durable and scoped to the user. Successful replays return the original creation snapshot; restock/adjustment have no deduplication. |
| Product editing | PUT replaces metadata only. Stock changes use restock or signed adjustment; sale status uses availability PATCH. DELETE refuses referenced history. |

## Tech Stack

| Area | Technology |
| --- | --- |
| Language / build | Java 21, Maven 3.9.11 via Maven Wrapper |
| Application | Spring Boot 3.5.16, Spring Web / MVC |
| Persistence | Spring Data JPA, Hibernate, Spring JDBC for key arbitration |
| Database / migrations | PostgreSQL 17, Flyway V1-V8 |
| Authentication | Spring Security 6.5.11, OAuth2 Resource Server / JOSE, Nimbus JWT |
| Password storage | Spring BCryptPasswordEncoder, cost 12 |
| Validation | Jakarta Validation and service guards |
| API documentation | OpenAPI 3.0, springdoc-openapi 2.10.0, Swagger UI |
| Health | Actuator with status-only health |
| Tests | JUnit 5, Mockito, MockMvc, Spring Security Test, PostgreSQL Testcontainers |
| Local / CI | Docker Compose, GitHub Actions |

Spring Boot manages security/Nimbus dependency versions in [pom.xml](pom.xml). springdoc is pinned using its [Boot 3.5 compatibility matrix](https://springdoc.org/v2/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot). JWT parsing, MAC verification, expiry checks, and password hashing use maintained libraries, not custom cryptographic implementations.

## Features

- Product CRUD with protected writes; case-insensitive name search, inclusive price filtering, stock filtering, sorting, and database pagination.
- Active-only public catalog, ADMIN availability changes, and a protected ADMIN catalog for inactive products; stock and history are retained.
- Multi-product ordering, BigDecimal totals, original price snapshots, and stock checks under ascending product-ID write locks.
- CREATED -> CONFIRMED -> SHIPPED -> DELIVERED, or CREATED -> CANCELLED; repeated/invalid transitions return 409.
- Deduct stock at creation only; cancel restores stock once. Fulfillment does not deduct stock again.
- Manual restock, signed stock adjustments, and paginated inventory history with opening/order/manual movements.
- Optional persistent Idempotency-Key scoped to the authenticated user, including concurrent retries and rollback recovery.
- CUSTOMER registration, BCrypt password hashes, short-lived JWT login, role authorization, and database ownership checks.
- Explicit operator-only admin bootstrap, structured errors, DTO-only responses, Swagger documentation, and an opt-in local demo.

## Architecture

```mermaid
flowchart LR
    Client[Client] --> Security[Spring Security JWT and role checks]
    Security --> Controller[Controller and validated DTO]
    Controller --> Service[Service: authorization and transaction]
    Service --> Repository[Repository: JPA or JDBC]
    Repository --> DB[(PostgreSQL)]
```

Controllers define HTTP contracts. Services enforce method permissions and order ownership as well as business rules. Repositories execute SQL filtering/pagination and pessimistic locks. Constructor injection is used throughout; Lombok is not used. Entities and password hashes are never returned directly. `open-in-view=false` keeps lazy access inside service transactions.

JWT authentication uses Spring Security's bearer-token filter. [JwtConfig](src/main/java/com/example/ordermanagement/security/JwtConfig.java) pins HS256 and validates timestamps, issuer, audience, subject, and role. [SecurityConfig](src/main/java/com/example/ordermanagement/security/SecurityConfig.java) also verifies that the database user still exists and the role still matches the token. This adds a database read per authenticated HTTP request; there is no HTTP session or token cache.

Packages: `auth/` contains user persistence, DTOs, login/registration, and bootstrap; `security/` contains JWT and authorization configuration; `product/`, `order/`, and `inventory/` contain business code; `common/` contains errors and page DTOs; `config/` contains OpenAPI configuration.

## Database Schema and Relationships

```mermaid
erDiagram
    app_users o|--o{ customer_orders : owns
    customer_orders ||--|{ order_items : contains
    products ||--o{ order_items : referenced_by
    products ||--o{ stock_movements : history
    customer_orders o|--o{ stock_movements : reference
    app_users o|--o{ order_idempotency : key_scope
    customer_orders ||--o| order_idempotency : creation_receipt
```

| Table | Key fields and constraints |
| --- | --- |
| `app_users` | Identity ID, unique normalized email, 60-character BCrypt hash, CUSTOMER/ADMIN role, created_at |
| `products` | Name/description, NUMERIC(19,2) price, nonnegative INTEGER stock, NOT NULL active BOOLEAN DEFAULT TRUE |
| `customer_orders` | Status CHECK, NUMERIC(25,2) total, created_at, nullable legacy user_id FK |
| `order_items` | Order/product FKs, positive quantity, price/subtotal snapshots; unique (order_id, product_id) |
| `stock_movements` | Signed delta, stock before/after, type, optional order/reason, timestamp; balance/type CHECKs |
| `order_idempotency` | Surrogate ID, user FK, client key, canonical payload, order FK, JSON response snapshot, timestamp |

Migration [V6](src/main/resources/db/migration/V6__users_order_ownership_and_scoped_idempotency.sql) adds users and ownership without changing old prices, statuses, stock, or journal entries. Existing orders retain `user_id = NULL` and are ADMIN-only; no owner or account is invented. New orders require an owner in the entity factory/service. The column remains nullable to preserve legacy data and allow legacy lifecycle updates; direct SQL is outside application authorization.

Old idempotency receipts are retained with NULL user_id and their exact payload/response text. They are archived outside authenticated users' replay scope. New calls use a partial unique index on `(user_id, idempotency_key)`; a separate partial unique index preserves legacy-key uniqueness. An old unscoped key does not recover an old order after this security upgrade. ADMIN can inspect that order by its ID. V1-V5 remain unchanged.

Migration [V7](src/main/resources/db/migration/V7__product_availability.sql) adds `products.active BOOLEAN NOT NULL DEFAULT TRUE`. Every existing product remains active and new products default to active in both Java and PostgreSQL. V1-V6 are unchanged. No stock, order, price snapshot, or inventory movement is rewritten.

Migration [V8](src/main/resources/db/migration/V8__manual_stock_adjustments.sql) extends only `ck_stock_movement_type` to accept MANUAL_ADJUSTMENT. V1-V7 and existing journal rows remain unchanged. The existing balance CHECK uses BIGINT arithmetic; nonnegative stock CHECKs, INTEGER storage bounds, foreign keys and history indexes remain in force. No new index is needed: adjustments use the existing product primary-key lock and product/time history index.

User ownership, receipt-order and movement-reference foreign keys use RESTRICT. Order items use ON DELETE CASCADE for their parent order; there is no order-deletion API, and journal/receipt references protect recorded orders. A user_id index supports order FK checks; the scoped key index also covers receipt user FK lookups. Stock history uses `(product_id, created_at DESC, id DESC)`. The price/ID index supports selective price pages. No plain name index is claimed to accelerate `%keyword%`; pg_trgm is not installed. See [ProductSearchIT](src/test/java/com/example/ordermanagement/integration/ProductSearchIT.java) for generated SQL and measured query plans, not a throughput benchmark.

## API Endpoints

Base URL: `http://localhost:8080`. JSON bodies. Supply `Authorization: Bearer <access-token>` on protected endpoints.

| Method | Endpoint | Permission | Success |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | Public; always creates CUSTOMER | 201 |
| POST | `/api/auth/login` | Public | 200, access token |
| GET | `/api/products` | Public | 200, page |
| GET | `/api/products/{id}` | Public | 200 |
| POST | `/api/products` | ADMIN | 201 + Location |
| PUT | `/api/products/{id}` | ADMIN; replace metadata only | 200 |
| DELETE | `/api/products/{id}` | ADMIN; no order/movement references | 204, empty body |
| POST | `/api/products/{id}/restock` | ADMIN | 200 |
| POST | `/api/products/{id}/adjust-stock` | ADMIN; signed nonzero delta, required reason | 200 |
| PATCH | `/api/products/{id}/availability` | ADMIN; set active, no stock change | 200 |
| GET | `/api/admin/products` | ADMIN; active/inactive catalog | 200, page |
| GET | `/api/products/{id}/stock-movements` | ADMIN | 200, page |
| POST | `/api/orders` | CUSTOMER or ADMIN; owner from principal | 201, or 200 replay |
| GET | `/api/orders` | CUSTOMER: own orders; ADMIN: all, including legacy | 200, summary page |
| GET | `/api/orders/{id}` | CUSTOMER owner or ADMIN | 200 |
| POST | `/api/orders/{id}/confirm` | ADMIN; CREATED -> CONFIRMED | 200 |
| POST | `/api/orders/{id}/ship` | ADMIN; CONFIRMED -> SHIPPED | 200 |
| POST | `/api/orders/{id}/deliver` | ADMIN; SHIPPED -> DELIVERED | 200 |
| POST | `/api/orders/{id}/cancel` | ADMIN; CREATED -> CANCELLED | 200 |

Customer lookups use `findByIdAndUserId`; another customer's order and an absent order both return 404. ADMIN reads any order, including legacy unowned records. HTTP routes and service `@PreAuthorize` checks enforce roles. No client `role` or `userId` can override this: unknown JSON fields are rejected.

### Product metadata updates

POST `/api/products` keeps `ProductRequest` with required initial `stockQuantity`. PUT `/api/products/{id}` uses `UpdateProductRequest` with **only name, description and price**. Name and price are required; omitted/null description clears it. Metadata validation is unchanged: nonblank name <=120, optional description <=2000, price >=0.01 with at most 12 integer/2 fractional digits.

```http
PUT /api/products/1
Authorization: Bearer <ADMIN_ACCESS_TOKEN>
Content-Type: application/json

{"name":"Mechanical Keyboard","description":"Updated USB keyboard","price":64.90}
```

200 returns the complete `ProductResponse`, including the current `stockQuantity` and `active` flag. Both are preserved by PUT. This intentionally replaces the old contract: **remove stockQuantity from PUT bodies**. Sending it now returns **400**, even if equal to current stock, under the existing `fail-on-unknown-properties=true` rule. `active` is also rejected; use availability PATCH. Use restock for stock additions or adjust-stock for signed corrections. POST still requires stockQuantity and creates INITIAL_STOCK.

ProductService retains its write transaction, ADMIN method authorization, and product-row pessimistic lock. The entity constructor and `updateMetadata` share metadata validation; only the constructor sets initial stock. A metadata edit never writes a client stock value or creates an inventory movement. If an Order/restock changes inventory before a waiting PUT obtains the lock, PUT preserves that latest quantity. Orders that already exist retain their original unit-price/subtotal/total snapshots. Concurrent stale metadata edits still follow the existing last-writer behavior; this refactor does not add ETag/version preconditions. No schema migration or new framework is needed.

### Product availability

Public `GET /api/products` always filters `active=true` **before** pagination and count. Public `GET /api/products/{id}` returns 404 for inactive products, including when called by ADMIN. Adding `active=false` to a public URL cannot expose them. `ProductResponse` includes `active` in create/update/restock/adjustment/availability responses and both catalog views.

ADMIN uses `GET /api/admin/products?active=false` to find inactive products. `active=true` selects active products; omitted selects both. The same keyword/minPrice/maxPrice/inStock/page/size/sort rules apply as the public catalog. Both SecurityConfig and service method security require ADMIN. CUSTOMER receives 403, anonymous callers 401. ADMIN can use PUT, restock, adjustment, inventory history and availability endpoints with an inactive product ID; metadata updates/restock/adjustment do not automatically reactivate it.

```http
PATCH /api/products/1/availability
Authorization: Bearer <ADMIN_ACCESS_TOKEN>
Content-Type: application/json

{"active":false}
```

Illustrative 200 response:

```json
{"id":1,"name":"Mechanical Keyboard","description":"USB keyboard","price":59.90,"stockQuantity":20,"active":false}
```

Send `{"active":true}` to reactivate. `active` is required and non-null (400 otherwise); unknown JSON fields such as stockQuantity are rejected. A missing product returns 404. Repeating the same value returns 200 with the same state, without changing stock or adding a movement. Availability is separate from inventory; it does not introduce a new movement type. Creation and PUT DTOs cannot set active; use this PATCH.

Ordering and availability changes take the same `PESSIMISTIC_WRITE` product-row lock. OrderService checks active **after acquiring locks** and before mutating any stock: inactive returns 409 and the whole transaction, including any idempotency claim, rolls back. If deactivation commits first, a waiting create sees inactive and fails. If an order gets the lock first and commits, it remains valid; deactivation then stops subsequent purchases. If deactivation rolls back, the waiter can still buy. Multi-product lock ordering remains ascending ID.

Existing orders remain readable and keep their original financial snapshots. ADMIN can cancel an existing CREATED order and restore its stock even if a product is inactive; availability stays false. Inventory history remains available to ADMIN. DELETE still rejects order/movement references, including INITIAL_STOCK: deactivation is the way to stop selling without deleting history. A successful idempotent creation retry still replays its original order after deactivation; it is not a new purchase. Failed inactive purchases do not consume the key and may retry after reactivation.

No boolean-only index is added without query-plan evidence; existing product search indexes remain in use. Availability is a sale flag, not a timestamped audit log or a replacement for lifecycle/stock validation.

### Order listing, filtering, and pagination

`GET /api/orders` accepts optional `status`, `from`, `to`, `page`, and `size`. CUSTOMER scope always comes from the authenticated principal; ADMIN can list every owner and legacy orders with `user_id = NULL`. There is no client owner filter. Unrecognized query parameters (including `userId` and `sort`) are ignored and cannot change the server's scope or ordering.

| Parameter | Contract |
| --- | --- |
| status | Optional, case-sensitive enum: CREATED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED |
| from / to | Optional inclusive createdAt bounds in ISO-8601 with `Z` or a numeric offset; encode `+` as `%2B` in a URL. Local dates/times without a timezone are rejected. `from` must not be after `to`. |
| page | Zero-based integer, default 0; nonnegative |
| size | Integer 1-100, default 20; `page * size` must not exceed 2147483647 |

Filters combine with AND **before** database pagination. Fixed sorting is `createdAt DESC, id DESC`; the ID breaks timestamp ties. Spring Data JPA Specification includes the ownership condition in both the result and count queries. The fluent DTO projection selects only `id`, `status`, `totalAmount`, `createdAt`; it does not load OrderItem or AppUser entities. This follows the [Spring Data DTO projection approach](https://docs.spring.io/spring-data/jpa/reference/3.5/repositories/projections.html). No schema change/migration is needed. The existing user_id index remains available for customer filtering; large result sets may still require database sorting, and no performance benchmark is claimed.

Success returns the existing `PageResponse` shape. `totalElements` counts only rows matching the caller's scope and filters. No matches or a page beyond the result set returns 200 with empty `content` and the correct totals. Invalid status/date/range/page/size/offset returns 400; no authentication returns 401. `GET /api/orders/{id}` still returns the unchanged detailed `OrderResponse` with items.

```http
GET /api/orders?status=CREATED&from=2026-11-01T00:00:00Z&to=2026-11-30T23:59:59Z&page=0&size=20
Authorization: Bearer <CUSTOMER_ACCESS_TOKEN>
```

Illustrative response for one matching order owned by the caller:

```json
{"content":[{"id":1,"status":"CREATED","totalAmount":119.80,"createdAt":"2026-11-21T06:01:00Z"}],"page":0,"size":20,"totalElements":1,"totalPages":1,"last":true}
```

### Validation, HTTP status, and errors

- Registration: normalized (trimmed/lowercased) email up to 254 characters; nonblank password 12-72 characters and at most 72 UTF-8 bytes. Passwords are never trimmed. Login validates email, nonblank password, and the same byte ceiling. Duplicate normalized emails return 409, including concurrent inserts.
- Product: nonblank name <=120, optional description <=2000, price >=0.01 with at most 12 integer/2 fractional digits. POST requires initial stock 0..2147483647; PUT accepts metadata only and any stockQuantity field returns 400.
- Order: 1-100 distinct non-null items, positive product IDs, integer quantity 1..1000000. Prices/totals are server-calculated. Missing products return 404; insufficient stock returns 409.
- Restock: quantity 1..2147483647, required trimmed reason <=500; resulting stock must fit INTEGER. Successful restock is not idempotent and adds stock again if repeated.
- Adjustment: required signed INTEGER `delta` !=0; required trimmed, nonblank `reason` <=500. Zero/missing/fractional/out-of-INTEGER input returns 400. A valid delta that would make stock negative or exceed 2147483647 returns 409 without changing stock/history.
- Search: optional `keyword` (trimmed, <=120, literal case-insensitive substring), inclusive nonnegative `minPrice`/`maxPrice` with min <= max, `inStock=true` (>0), false (=0), or omitted. All filters combine with AND.
- Pages: page >=0, size 1..100, page*size <=2147483647. Product defaults: page=0,size=20,sort=id,asc; sort fields id/name/price/stockQuantity, optional asc/desc. Non-ID sorts add ID ascending. History permits only createdAt,asc or createdAt,desc (default desc), with ID in the same direction for ties.

Errors use ApiError: `timestamp`, `status`, `error`, `message`, `path`, `fieldErrors`. 400 means invalid input; 401 means missing/invalid/expired bearer token or invalid credentials; 403 means authenticated but lacking role permission; 404 means absent/not-owned resource; 409 means business/idempotency/locking conflict; 500 hides internal details. Protected routes authenticate before validating business input. Authentication database/backend failures return generic 500 rather than misclassifying an outage as invalid credentials. Invalid login credentials have the same generic message whether the email exists or not. Registration intentionally reports duplicate emails with 409. Successful login is `Cache-Control: no-store`; no session cookie is issued.

### Idempotent order creation

Optional `Idempotency-Key`: 1-128 case-sensitive ASCII letters/digits or `.`, `_`, `:`, `-`, exactly one value without spaces. Scope is the authenticated user. Without it, each successful request creates a new order. New key: 201 + Location + `Idempotency-Replayed: false`. Same user/key/equivalent payload: 200 + Location + `Idempotency-Replayed: true`, returning the original creation snapshot without another stock/journal write. A different valid payload for that user/key returns 409. Different users may reuse the same key independently.

Payload identity is the sorted set of `(productId,quantity)` pairs; JSON whitespace, property ordering, and item ordering are insignificant. Duplicates still return 400. Replays keep original prices and CREATED status even after cancellation/fulfillment; GET returns current state. Keys are retained indefinitely; deletion/expiry is not implemented. Restock and stock adjustment do not support keys.

The coordinator claims `(user_id,key)` with `INSERT ... ON CONFLICT DO NOTHING` before product locks in READ COMMITTED. A competing insert waits for commit/rollback; a separate SELECT statement obtains the newly committed receipt. Rollback lets a waiting request claim and create. OrderService joins the coordinator's REQUIRED transaction; JDBC and Hibernate share it. A deferred database trigger rejects incomplete reservations at commit. Failures are not cached; a five-second lock timeout returns retryable 409 (Spring's PostgreSQL SQLSTATE translator handles 55P03). An indefinitely stalled live owner still needs database timeout/operator intervention; no lease takeover is implemented. These guarantees apply to retained receipts in this database, not exactly-once in all conditions. See [PostgreSQL unique checks](https://www.postgresql.org/docs/17/index-unique-checks.html) and [READ COMMITTED](https://www.postgresql.org/docs/17/transaction-iso.html).

### Stock adjustment and inventory history

INITIAL_STOCK records opening stock, including zero; ORDER_DEDUCTION is negative with an order reference; ORDER_CANCELLATION is positive with an order reference; MANUAL_RESTOCK is positive with a reason. MANUAL_ADJUSTMENT has a signed nonzero delta, a required reason, and no order reference. Every movement stores before/after snapshots and createdAt. No prior history is backfilled for legacy products. History is immutable through the API, not a tamper-proof audit system against database operators. Referenced products cannot be deleted, so new products with opening history return 409 on deletion; only unused legacy products can return 204.

ADMIN can correct damaged/miscounted inventory without changing metadata or availability:

```http
POST /api/products/1/adjust-stock
Authorization: Bearer <ADMIN_ACCESS_TOKEN>
Content-Type: application/json

{"delta":-3,"reason":"Damaged products"}
```

For a product named Keyboard priced 59.90 with stock 10, this returns 200:

```json
{"id":1,"name":"Keyboard","description":null,"price":59.90,"stockQuantity":7,"active":true}
```

The history endpoint then includes a MANUAL_ADJUSTMENT with `quantityDelta=-3`, `stockBefore=10`, `stockAfter=7`, `referenceOrderId=null`, the trimmed reason and a server timestamp. Use a positive delta, for example `{"delta":5,"reason":"Stock count correction"}`, to increase stock. Inactive products can also be adjusted; they stay inactive. Restock retains its existing positive-quantity contract and MANUAL_RESTOCK type.

[ProductService.adjustStock](src/main/java/com/example/ordermanagement/product/ProductService.java) starts a write transaction, acquires the same `PESSIMISTIC_WRITE` product-row lock used by ordering/restock/cancellation, reads stock before, and calls [Product.adjustStock](src/main/java/com/example/ordermanagement/product/Product.java). The domain method computes `(long) stockQuantity + delta`, checks both bounds, then changes state. The service saves the immutable movement in that same transaction. Any failed movement INSERT or stock UPDATE rolls back both. A waiting operation rechecks the committed stock after taking the lock. Adjustment locks only one product and never an order; multi-product orders still lock ascending product IDs, and cancellation locks the order first. No new lock-order cycle is introduced.

**Retry behavior:** adjustment is not idempotent. Repeating a successful POST applies the delta and adds a new movement again; an Idempotency-Key header does not deduplicate it. If a response is lost, inspect history/reconcile before retrying blindly. Only order creation currently implements persistent idempotency.

## How to Run

Requirements: JDK 21 for host execution; Docker Desktop in Linux-container mode with Compose v2/BuildKit; internet for first dependencies/images. No separate Maven installation is needed. Clone `https://github.com/22127433/order-management-api.git` and run commands from its root.

### Windows: initialize secrets and provision the first admin

```powershell
.\scripts\init-local.ps1
notepad .env
```

The initializer copies `.env.example` if necessary and generates a cryptographically random 32-byte Base64 JWT key only if missing/empty. It preserves an existing key and does not print it. In your ignored `.env`, set POSTGRES_PASSWORD. To provision an admin on first startup, set BOOTSTRAP_ADMIN_ENABLED=true and provide your own BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD (same validation as registration). There are no default credentials. Quote values containing `$` or `#` with single quotes. Never commit `.env`, JWTs, or actual credentials.

Bootstrap is disabled by default. When explicitly enabled, it creates ADMIN with BCrypt. It refuses to promote an existing CUSTOMER and never resets an existing admin password silently. After the first successful startup, set BOOTSTRAP_ADMIN_ENABLED=false and clear BOOTSTRAP_ADMIN_PASSWORD in `.env`; restart/recreate the API. Existing admin credentials keep working. No role-changing or password-reset endpoint is implemented.

### Windows: PostgreSQL in Docker, API on the host

```powershell
.\scripts\load-env.ps1
docker compose up -d --wait postgres
.\mvnw.cmd spring-boot:run
```

Or use `.\scripts\run-local.ps1`. Spring Boot launched through Maven does not automatically load `.env`; the loader safely reads values as data into this process environment. Reload after editing it. Database-only `docker compose up -d` keeps working without JWT configuration. For an existing database, set DB_HOST/DB_PORT and use `run-local.ps1 -SkipDatabase`.

### Windows: both API and PostgreSQL in Docker

After configuring `.env`, no host JDK is required:

```powershell
docker compose --profile app up -d --build --wait
docker compose logs -f api
```

Default Compose without the app profile starts only PostgreSQL. The app profile waits for database health, then checks API `/actuator/health`. Dockerfile uses Maven 3.9.11/Java 21, a BuildKit Maven cache, unit/MVC tests during packaging, and a non-root Java 21 runtime. PostgreSQL integration tests run separately. Host ports bind to 127.0.0.1.

After changing bootstrap flags/password in `.env`, recreate the API with `docker compose --profile app up -d --wait api`. Stop containers while retaining data with `docker compose --profile app down`. The postgres_data volume persists data; changing POSTGRES_PASSWORD in `.env` does not change an initialized database's password. If ports are busy, adjust POSTGRES_PORT/SERVER_PORT before startup. Stop host Java with Ctrl+C.

### Swagger UI and health

- [Swagger UI](http://localhost:8080/swagger-ui/index.html): run register/login, then click **Authorize** and paste only the returned accessToken (Swagger adds Bearer). Use CUSTOMER or ADMIN credentials for the operation's permission.
- [OpenAPI JSON](http://localhost:8080/v3/api-docs), [YAML](http://localhost:8080/v3/api-docs.yaml): 19 documented business operations on 15 paths, with DTOs, errors, and security requirements.
- [Health](http://localhost:8080/actuator/health): status only, 200 UP / 503 DOWN. Other management endpoints are not exposed and security denies their requests (401 without authentication, 403 when authenticated). Environment/configprops/heapdump are not public. JMX endpoints and probes are disabled.

Set API_DOCS_ENABLED=false to disable both Swagger and the spec. Authorization is not persisted by Swagger across reloads. Public endpoints can still return 401 if an invalid bearer token is explicitly supplied. The root `/` is not an API endpoint.

### Windows: guided local demo

With the API running, open another PowerShell terminal:

```powershell
.\scripts\demo.ps1
```

It prompts for the local ADMIN credential, logs in, registers a fresh CUSTOMER, and uses that customer for ordering/retries. ADMIN performs catalog, inventory, and lifecycle actions. It accepts loopback HTTP(S) URLs only; use `-BaseUrl http://localhost:8081` for a different port. It does not print passwords or JWTs. Each run intentionally adds one CUSTOMER, two uniquely named products, and two orders. IDs and the non-secret idempotency key are printed for inspection as ADMIN in Swagger. These records remain; no startup product/order seed or automatic history deletion occurs.

The script checks this sequence using actual returned IDs and HTTP statuses:

1. Register/login -> create keyboard (20 units) and mouse (10) -> case-insensitive search with price/stock filters and pagination -> metadata-only PUT.
2. Create a two-product order (139.79), retry the same key (200, same order), reject a different payload (409), and verify keyboard stock 18. CUSTOMER sees only its order summary; anonymous listing returns 401.
3. ADMIN confirms, ships and delivers **that same keyed order**, checking stock stays 18 at each step. CUSTOMER cannot confirm; cancellation/repeated delivery of the delivered order return 409.
4. Deactivate twice; public detail returns 404 and search hides it. ADMIN still lists it. New purchases return 409; the existing order remains readable and the committed key still replays its original CREATED snapshot.
5. While inactive, adjust -3 (damaged), restock +5, adjust +2 (count correction): stock becomes 22 without reactivating. Reactivate; retry the earlier rejected key successfully, then cancel that second order once. CUSTOMER order pagination returns two summaries.
6. Read stock history, validate the full before/delta/after chain and rejection cases. Final keyboard stock is **22**, active=true, with **7 movements**: `+20, -2, -3, +5, +2, -1, +1`.

For manual requests, use the presentation route at the top of [requests.http](requests.http). Its numbered examples are an extended regression collection with their own stock assumptions; they are not the same sequence as the automated demo. The fulfillment request includes a key and an adjacent named retry before confirm/ship/deliver.

### Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| POSTGRES_PASSWORD | Required | Database password |
| POSTGRES_DB / POSTGRES_USER | order_management / order_app | Database name/user |
| POSTGRES_PORT / SERVER_PORT | 5432 / 8080 | Published host ports |
| DB_HOST / DB_PORT | localhost / POSTGRES_PORT | Host Java database overrides |
| JWT_SECRET_BASE64 | Required for API; no fallback | At least 32 random bytes, Base64-encoded |
| BOOTSTRAP_ADMIN_ENABLED | false | Explicit one-time operator action |
| BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD | Empty | Operator-supplied bootstrap credentials |
| API_DOCS_ENABLED | true | Swagger/spec toggle |

Compose sets internal database host=postgres, port=5432, and API port=8080. JWT issuer/audience are order-management-api; TTL is 15m in application.yml. Configuration validates TTL from 1 minute to 1 hour. Invalid/missing signing keys fail startup. .gitignore and .dockerignore exclude real env files, credentials, private keys, caches, and build output.

## Example Requests & Responses

[requests.http](requests.http) uses VS Code REST Client prompts for credentials so secrets need not be written into the tracked file. Register a CUSTOMER and run CUSTOMER/ADMIN login requests first, then numbered examples. Named responses supply actual IDs and tokens. Login again after access-token expiry. Swagger example IDs are illustrative, not existing records. The placeholders below must be replaced locally; do not commit real tokens/passwords.

Register and log in:

```http
POST /api/auth/register
Content-Type: application/json

{"email":"customer@example.com","password":"<YOUR_LOCAL_CUSTOMER_PASSWORD>"}
```

201 returns only id, email, role=CUSTOMER, createdAt. POST `/api/auth/login` with the same fields returns 200:

```json
{"accessToken":"<ACCESS_TOKEN>","tokenType":"Bearer","expiresIn":900,"user":{"id":2,"email":"customer@example.com","role":"CUSTOMER","createdAt":"2026-11-18T06:00:00Z"}}
```

Create Product (ADMIN):

```http
POST /api/products
Authorization: Bearer <ADMIN_ACCESS_TOKEN>
Content-Type: application/json

{"name":"Keyboard","description":"USB","price":59.90,"stockQuantity":20}
```

201 + Location returns `{ "id":1,"name":"Keyboard","description":"USB","price":59.90,"stockQuantity":20,"active":true }`.

List Products (public):

```http
GET /api/products?keyword=KEYboard&minPrice=10&maxPrice=100&inStock=true&page=0&size=10&sort=price,asc
```

200 returns `content`, zero-based `page`, `size`, filtered `totalElements`, `totalPages`, `last`.

Create Order (CUSTOMER or ADMIN):

```http
POST /api/orders
Authorization: Bearer <CUSTOMER_ACCESS_TOKEN>
Idempotency-Key: checkout-example-1
Content-Type: application/json

{"items":[{"productId":1,"quantity":2}]}
```

Illustrative 201 response (assuming authenticated user ID 2):

```json
{"id":1,"status":"CREATED","totalAmount":119.80,"createdAt":"2026-11-18T06:01:00Z","items":[{"id":1,"productId":1,"quantity":2,"unitPrice":59.90,"subtotal":119.80}],"userId":2}
```

Get Order: `GET /api/orders/1` with its owner's token or an ADMIN token -> 200. A different CUSTOMER token -> 404. Cancel Order: `POST /api/orders/1/cancel` with ADMIN -> 200 CANCELLED, stock restored once; repeat -> 409. CUSTOMER cancellation -> 403. For a separate order, ADMIN calls confirm -> ship -> deliver; stock is unchanged by these actions.

Validation Error: creating an order with quantity=0 returns 400 after authentication:

```json
{"timestamp":"2026-11-18T06:02:00Z","status":400,"error":"Bad Request","message":"Request validation failed","path":"/api/orders","fieldErrors":[{"field":"items[0].quantity","message":"must be greater than 0"}]}
```

Insufficient Stock Error: with 18 keyboards remaining, quantity=21 returns 409 with message `Insufficient stock for product 1: requested 21, available 18`; stock/order/receipt/journal changes roll back. Invalid token returns 401 with WWW-Authenticate: Bearer; valid CUSTOMER attempting restock returns 403. Every error retains the shared ApiError shape.

## Testing

Run from the repository root on Windows:

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify -Pintegration
```

Linux/macOS:

```sh
./mvnw test
./mvnw verify -Pintegration
```

The first command runs unit/MVC tests without Docker, a database, .env or production secrets. The integration profile also runs real PostgreSQL Testcontainers; Docker must be available. Test credentials/signing keys are generated at runtime. Surefire discovers *Test, Failsafe discovers *IT. `verify` includes the unit/MVC suite again; those repeated executions are not added twice to the totals below.

Local final verification on **21 November 2026**, Windows / Java 21 / Maven Wrapper 3.9.11: both commands completed with **BUILD SUCCESS**. The standalone `test` ran **411** cases. The `verify -Pintegration` run passed **593** cases across **27** classes, with **zero failures, errors or skipped tests**:

| Group | Passed | Evidence |
| --- | ---: | --- |
| Unit | 212 | Domain/Mockito service tests and real Nimbus JWT validator tests; no database |
| MVC slice | 199 | HTTP binding, validation, error shape and security filters; business services/repositories mocked |
| PostgreSQL integration | 182 | Real JPA/JDBC transactions, SQL constraints, ownership, HTTP authentication, migrations and controlled lock races |

XML reports are generated in `target/surefire-reports` and `target/failsafe-reports` and excluded from Git. [FINAL_REVIEW.md](FINAL_REVIEW.md) records the review scope and remaining risks. Counts include parameterized cases; they are not coverage percentages or performance results.

| Verification area | Main integration evidence |
| --- | --- |
| JWT, BCrypt, permissions, ownership, scoped keys | [AuthenticationAuthorizationIT](src/test/java/com/example/ordermanagement/integration/AuthenticationAuthorizationIT.java) |
| Order lifecycle, stock restoration, price snapshots, metadata/order locking | [OrderManagementIT](src/test/java/com/example/ordermanagement/integration/OrderManagementIT.java) |
| Durable replay, payload mismatch, competing claims, rollback/retry | [OrderIdempotencyIT](src/test/java/com/example/ordermanagement/integration/OrderIdempotencyIT.java) |
| Search SQL/price index, database pagination and literal keyword handling | [ProductSearchIT](src/test/java/com/example/ordermanagement/integration/ProductSearchIT.java) |
| Inactive public visibility, ADMIN catalog, deactivation/order races | [ProductAvailabilityIT](src/test/java/com/example/ordermanagement/integration/ProductAvailabilityIT.java) |
| CUSTOMER scope, ADMIN legacy orders, summary projection without item queries | [OrderListingIT](src/test/java/com/example/ordermanagement/integration/OrderListingIT.java) |
| Restock/adjustment, history, overflow, SQL failure rollback, inventory races | [InventoryManagementIT](src/test/java/com/example/ordermanagement/integration/InventoryManagementIT.java) |
| Empty database and each V1-V6 baseline upgraded to V8 with existing data | [MigrationCompatibilityIT](src/test/java/com/example/ordermanagement/integration/MigrationCompatibilityIT.java) |
| All 19 operations: DTOs, permissions in documentation, success/error statuses, Swagger HTTP assets | [ApiDocumentationIT](src/test/java/com/example/ordermanagement/integration/ApiDocumentationIT.java) |

Rollback tests deliberately fail SQL writes and inspect persisted state after the transaction ends. Controlled concurrency tests observe PostgreSQL `pg_stat_activity.wait_event_type = 'Lock'` before releasing the first transaction. Mockito tests do not prove either behavior; these races do not exhaust all possible schedules.

The [GitHub Actions workflow](.github/workflows/ci.yml) checks Docker, then runs `./mvnw -B -ntp verify -Pintegration` on push/PR with Java 21 and a 20-minute timeout. This includes unit/MVC tests, fresh migration, V1-V6 upgrades and all PostgreSQL integration tests. The Unix wrapper is versioned executable with LF endings. This review ran locally on Windows; a GitHub Actions run is not claimed here.

Docker Compose smoke verification on **21 November 2026** also built the API with the current Dockerfile and ran `scripts/demo.ps1` twice: first on a fresh database, then on the populated database. Both runs completed with stock 22 and seven movements per new keyboard. Headless Chrome rendered all 19 Swagger operations with Authorize. PostgreSQL-only Compose worked without JWT configuration; login and an existing token still worked after recreating the API with bootstrap disabled and its password removed. The isolated verification stack/volume and temporary credentials were cleaned up. These smoke checks are additional to the JUnit count.

## Design Decisions

- **Password storage:** BCrypt cost 12 with a fresh salt; no reversible password encryption. Validate 72 UTF-8 bytes before hashing so long Unicode passwords cannot be silently truncated. Normalize emails, never passwords. Hashing/verification uses Spring's PasswordEncoder and DaoAuthenticationProvider. See [Spring password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
- **JWT:** HS256 is sufficient for one issuing/verifying monolith. A random environment-provided key is mandatory. Spring/Nimbus verifies signatures with the pinned algorithm, issuer/audience, required numeric subject/role/iat/exp, timestamps, and 30-second clock leeway. JWTs are signed, not encrypted; they contain no password/hash/email. Replacing the secret invalidates existing tokens. See [Spring Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
- **Identity freshness:** Each JWT request rechecks the database account and role. Deleted accounts or changed roles invalidate old tokens. No HTTP session, refresh token, logout endpoint, individual token denylist, password reset, or role-change API is implemented. Access tokens otherwise remain usable until expiry; production deployment would need HTTPS and an appropriate abuse/rate-limit policy.
- **Authorization:** Request matchers and method security check roles; repository ownership predicates protect Order reads. A role alone is not ownership. ADMIN-only cancellation preserves existing lifecycle/stock rules. User IDs/roles cannot be assigned through registration/order request DTOs.
- **CSRF/CORS:** CSRF is disabled because authentication uses explicit bearer headers with no cookie/basic/form session. This choice must be revisited if cookie authentication is introduced. No permissive cross-origin policy is added.
- **Transactions/locking:** Order creation, cancellation, restock, signed adjustment and movements retain their write transactions and pessimistic product locks. Products are locked in ascending ID order; lifecycle/cancel lock the order first. Adjustment locks one product, checks bounds using long arithmetic, and writes its movement in the same transaction. Idempotency claims precede product locks. Authorization does not replace database concurrency controls.
- **Migration compatibility:** Keep legacy owners/receipts NULL and ADMIN-only/archived rather than guessing user ownership. New application-created orders always have an owner. Schema nullability is an explicit compatibility limitation, not a claim that raw SQL obeys authorization. Core order URLs/payloads remain; protected endpoints require authentication, and metadata PUT intentionally excludes the former stockQuantity field.
- **Scope:** One application/database, constructor injection, DTO boundaries, explicit Flyway migrations, and targeted indexes. No extra infrastructure is needed for these portfolio features.

## Current Limitations and Technical Risks

- **Authentication operations:** No refresh tokens, individual token revocation/logout, password reset, rate limiting, or user/role administration. HS256 uses one environment-provided key; replacing it invalidates all tokens. Production hosting would need HTTPS and secret lifecycle management. Duplicate-email registration intentionally returns 409.
- **Retry retention:** Order receipts have no expiry/cleanup. Restock and adjustment are not idempotent; an uncertain successful response must be reconciled before retry. A live stalled transaction has no application lease takeover; the five-second lock timeout limits waiters, not total transaction duration.
- **Stock reservations:** CREATED orders hold deducted stock until an ADMIN fulfills/cancels them; there is no automatic expiry. If inventory has since been increased to capacity, cancellation can return 409 for overflow and leave order/stock/history unchanged.
- **Audit scope:** No history backfill for legacy stock; movements have no actor-user field, and availability changes have no audit history. Immutability is enforced through application mapping/API, not against direct database administrators. Legacy orders/receipts retain nullable ownership as documented.
- **Concurrency and scale:** Metadata edits use the product lock but have no @Version/ETag to detect stale edit intent. Page size/offset bounds do not make large offsets cheap. Name substring search has no trigram index; order filters may need composite indexes after representative EXPLAIN measurements. Controlled lock-race tests are not exhaustive stress tests or throughput benchmarks.
- **Business scope:** No payment, currency conversion, tax/discount calculation, carrier integration, frontend, or distributed transaction. Monetary values use a single implicit currency; production currency rules are not modeled.
