# Review Order Management API — 12/11/2026

Đã đọc toàn bộ production code, các test, migration, Maven Wrapper, `pom.xml`, cấu hình Spring Boot, Dockerfile, Docker Compose, script local và CI. Đã sửa trực tiếp một vấn đề kiểm tra đầu vào tại service và bổ sung test. Giữ nguyên kiến trúc Controller → Service → Repository và phạm vi chức năng.

## Kết luận theo yêu cầu

| Hạng mục | Kết quả kiểm tra |
| --- | --- |
| Compile/package | `mvnw.cmd test` compile main/test code thành công; `verify -Pintegration` đóng gói executable JAR thành công. |
| Unit/HTTP test | 21 Mockito service test và 34 MockMvc test đều pass. Các test mock không kiểm chứng transaction PostgreSQL. |
| PostgreSQL/Compose | PostgreSQL 17.11 thực chạy bằng Testcontainers. Flyway V1 áp dụng thành công; Hibernate `ddl-auto=validate` xác nhận schema phù hợp mapping. Compose config hợp lệ với password được cấp qua môi trường, từ chối password rỗng. |
| REST API | Cả 8 endpoint có MockMvc test; integration test gọi HTTP pipeline với service/repository thật để kiểm tra CRUD, pagination và vòng đời đơn. `Location`, DTO và các mã 201/200/204/400/404/409/500 có test. |
| Mapping JPA | `CustomerOrder.items` là inverse side `mappedBy=order`, cascade ALL/orphanRemoval; `OrderItem.order` giữ FK `order_id`, `OrderItem.product` giữ FK `product_id`; cả hai ManyToOne dùng LAZY. Không cascade thao tác xóa từ item sang Product. Quan hệ và giá snapshot đọc lại đúng từ DB. |
| Transaction tạo/hủy | Controller gọi public method trên Spring service được inject. `@Transactional` tại create/cancel ghi đè `readOnly=true` ở class. Các test không bọc toàn bộ test trong transaction, nên kiểm tra được commit và rollback thực của service. |
| Hai request đặt hàng | Test giữ transaction đầu chưa commit, quan sát `pg_stat_activity.wait_event_type='Lock'` ở request thứ hai, sau đó cho transaction đầu commit. Kết quả một thành công, một thiếu hàng, stock cuối 0, chỉ có một đơn. |
| Thiếu hàng/rollback | Thiếu một sản phẩm: kiểm tra toàn bộ stock trước khi mutate nên không có thay đổi từng phần. Test riêng gây lỗi UPDATE tồn kho sau INSERT đơn/items để kiểm chứng rollback SQL thực. Test hủy đơn gây lỗi ghi kho và xác nhận trạng thái cùng stock giữ nguyên. |
| Tiền tệ | Giá, subtotal và tổng dùng BigDecimal; phép nhân dùng BigDecimal.valueOf(quantity); scale 2, không dùng double/float. Test `0.10 × 3 = 0.30`, tổng nhiều sản phẩm `40.28`, snapshot giá và round-trip tổng lớn `999999999999990000.30` đều pass. |
| Error/validation | DTO có @Valid lồng nhau; quantity null/âm/0/vượt giới hạn, trường lạ, fractional integer, JSON lỗi, pagination và ID lỗi được kiểm tra. Advice trả ApiError nhất quán; lỗi 500 không trả stack trace/SQL nội bộ. |

## Vấn đề đã sửa

### Service thiếu kiểm tra đầu vào khi gọi ngoài controller

`OrderService.create` trước đây phụ thuộc hoàn toàn vào Bean Validation ở controller. Gọi service trực tiếp với đơn rỗng có thể tạo đơn không có item; quantity không hợp lệ có thể dẫn đến lỗi lookup hoặc lỗi runtime thay vì lỗi đầu vào nhất quán. HTTP controller hiện có đã chặn các payload này; đây là vấn đề ở điều kiện đầu vào của service, không phải bằng chứng API HTTP trước đó chấp nhận quantity âm.

Đã kiểm tra request, số dòng, item/productId và quantity ngay đầu method, trước mọi repository call hoặc thay đổi stock. Dữ liệu sai phát sinh `BadRequestException`. Giữ nguyên validation DTO để response HTTP vẫn có field errors.

Đã chạy regression test trước khi sửa: **6 test failure** cho quantity null/âm/0/quá giới hạn và đơn rỗng. Sau sửa, toàn bộ các test này pass và xác nhận repository không được gọi.

### Các khoảng trống test đã khắc phục

- Test tranh tồn kho/hủy đơn trước chỉ cho hai thread bắt đầu gần nhau, chưa bảo đảm chúng thực sự tranh khóa. Đã dùng latch giữ transaction đầu và truy vấn trạng thái chờ khóa thật trong PostgreSQL trước khi cho commit.
- Test rollback tạo đơn trước gây lỗi ngay khi INSERT đơn. Đã chuyển điểm lỗi sang UPDATE tồn kho bằng trigger chỉ tồn tại trong database test, để các INSERT đã xảy ra trước lỗi cũng phải rollback.
- Bổ sung rollback khi hủy đơn thất bại, HTTP CRUD với PostgreSQL thật, lifecycle tạo/đọc/hủy/hủy trùng, resource không tồn tại, quantity sai và tiền tệ ở biên giá trị lớn.

Không sửa thuật toán transaction, lock hoặc tính tiền vì chưa phát hiện lỗi trong các phần này sau kiểm chứng. Không thêm tính năng sản phẩm.

## Phân biệt các lớp test

| Test suite | Số test | Phạm vi |
| --- | ---: | --- |
| `OrderServiceTest` | 15 | JUnit 5/Mockito, mock repository: tạo đơn, thiếu/mất sản phẩm, quantity sai, trùng productId, hủy thành công, hủy trùng và các biên nghiệp vụ. |
| `ProductServiceTest` | 6 | JUnit 5/Mockito, mock repository: CRUD, pagination, không xóa sản phẩm có lịch sử đơn. |
| `ApiControllerTest` | 34 | MockMvc slice, mock service: HTTP contract, validation và error handling. |
| `OrderManagementIT` | 12 | Full Spring context + PostgreSQL/Testcontainers + repository thật: schema, persistence, commit/rollback, tranh khóa và HTTP pipeline. |
| **Tổng** | **67** | **0 failure, 0 error, 0 skip** |

Hai test cạnh tranh dùng transaction ngoài chỉ trong worker đầu để giữ khóa đủ lâu cho quan sát. Worker thứ hai và các test rollback gọi service bình thường qua Spring proxy. Các test rollback không dùng outer transaction của test, không dùng mock repository.

## Lệnh đã chạy và bằng chứng

Workspace dùng repository Maven riêng tại `.cache/repository` để không ghi vào Maven repository ngoài workspace. Dependencies đã được tải từ lần xây dựng trước nên chạy được offline. `-Ddebug=false` tắt debug được môi trường máy host bật sẵn.

```powershell
.\mvnw.cmd -o '-Dmaven.repo.local=D:\Document\order-management-api\.cache\repository' -B -ntp test '-Ddebug=false'
.\mvnw.cmd -o '-Dmaven.repo.local=D:\Document\order-management-api\.cache\repository' -B -ntp verify -Pintegration '-Ddebug=false'
docker compose --profile app config --quiet
```

Lệnh Compose được chạy với `POSTGRES_PASSWORD` ngẫu nhiên trong môi trường của tiến trình; không ghi hoặc log giá trị mật khẩu. Kiểm tra thêm xác nhận thiếu password khiến Compose từ chối cấu hình. Cú pháp script PowerShell local cũng hợp lệ.

- Unit/HTTP: **BUILD SUCCESS**, 55 test pass.
- Integration: **BUILD SUCCESS**, 12 test pass; lượt verify hoàn thành lúc 12:50 ngày 12/11/2026, giờ Việt Nam.
- Java: Temurin 21.0.6; Maven: 3.9.11; Docker Engine: 27.3.1; database: PostgreSQL 17.11.
- Artifact: `target/order-management-api-1.0.0.jar`.
- Báo cáo XML/text: `target/surefire-reports/`, `target/failsafe-reports/`.
- Log workspace: `.cache/review-unit.log`, `.cache/review-integration.log`, `.cache/review-regression-before.log`.

Các log ERROR từ test HTTP 500 và trigger `Forced inventory write failure` là lỗi được chủ động gây ra trong test; kết quả được xác nhận bằng báo cáo test và exit code Maven.

## Giới hạn kiểm chứng

Chưa build image API hoặc khởi động cả stack bằng Docker Compose. Phần runtime PostgreSQL được kiểm chứng bằng Testcontainers, HTTP được chạy bằng MockMvc full context, chưa gọi qua socket mạng hoặc reverse proxy. Cấu hình Dockerfile được review; Compose được kiểm tra cú pháp/interpolation/biến môi trường, không báo là đã chạy toàn bộ container stack.

`PUT Product` đặt tồn kho tuyệt đối theo contract hiện tại; client phải gửi số tồn thực tế mong muốn. API chưa hỗ trợ idempotency key cho create order. Đây là giới hạn phạm vi đã ghi trong README; đợt review này không tự thêm chức năng.
