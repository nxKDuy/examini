# Nền tảng khảo thí online ứng dụng AI

**Đồ án tốt nghiệp (DATN)** — Đại học Tôn Đức Thắng (TDTU)

## Tech Stack

| Layer | Công nghệ |
|---|---|
| Backend | Java 21 + Spring Boot 3.3, Microservices |
| Service Discovery | Spring Cloud Eureka |
| API Gateway | Spring Cloud Gateway + JWT Filter |
| Giao tiếp async | RabbitMQ |
| Giao tiếp sync | OpenFeign |
| Database | PostgreSQL 16 (database-per-service) |
| Migration | Flyway |
| Cache/Session | Redis 7 |
| Real-time | Spring WebSocket + STOMP |
| Frontend | React + Vite + TypeScript + TailwindCSS |
| Containerization | Docker + Docker Compose |
| AI | OpenAI / Claude API (Giai đoạn 4) |
| Auth | JWT (JJWT 0.12) + Spring Security |

## Microservices

| Service | Port | Trạng thái |
|---|---|---|
| eureka-server | 8761 | ✅ Giai đoạn 1 |
| api-gateway | 8080 | ✅ Giai đoạn 1 |
| auth-service | 8081 | ✅ Giai đoạn 1 |
| question-bank-service | 8082 | 🔄 Giai đoạn 2 |
| exam-service | 8083 | 🔄 Giai đoạn 2 |
| exam-session-service | 8084 | 🔄 Giai đoạn 2 |
| grading-service | 8085 | 🔄 Giai đoạn 3 |
| analytics-service | 8086 | 🔄 Giai đoạn 3 |

## Khởi động nhanh (Development)

### 1. Clone & setup môi trường

```bash
git clone <repo-url>
cd DATN
cp .env.example .env
# Chỉnh sửa .env: đặt JWT_SECRET mạnh (>=64 ký tự)
```

### 2. Khởi động infrastructure + services

```bash
docker compose up -d
```

### 3. Kiểm tra

- Eureka Dashboard: http://localhost:8761
- API Gateway: http://localhost:8080
- Auth Service Swagger: http://localhost:8081/swagger-ui.html
- RabbitMQ Management: http://localhost:15672 (user: datn / xem .env)

### 4. Test auth flow

```bash
# Đăng ký
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"student@test.com","password":"Test@1234","fullName":"Nguyễn Văn A"}'

# Đăng nhập
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"student@test.com","password":"Test@1234"}'

# Lấy thông tin user (thay YOUR_TOKEN)
curl http://localhost:8080/auth/me \
  -H "Authorization: Bearer YOUR_TOKEN"
```

## Khởi tạo Admin

Cấu hình `AUTH_BOOTSTRAP_EMAIL` và `AUTH_BOOTSTRAP_PASSWORD` trong môi trường triển khai (mật khẩu tối thiểu 16 ký tự). Không commit credentials. Bootstrap chỉ tạo/cập nhật tài khoản Admin, không nâng quyền tài khoản Student/Teacher có cùng email.

Migration V4 vô hiệu hóa mật khẩu seed cũ, giữ nguyên ID và dữ liệu người dùng. Với profile `prod`, cài đặt mới phải có credentials bootstrap hoặc một Admin đã được cấu hình. Sau khi bootstrap thành công, có thể bỏ cấu hình bootstrap khỏi môi trường để tránh ghi lại mật khẩu ở mỗi lần khởi động.

## Cấu trúc thư mục

```
DATN/
├── docker-compose.yml
├── .env.example
├── pom.xml                     ← Parent POM (multi-module Maven)
├── docker/postgres/init/       ← SQL tạo databases
├── eureka-server/
├── api-gateway/
├── auth-service/               ← Hoàn chỉnh (Giai đoạn 1)
├── question-bank-service/      ← Skeleton (Giai đoạn 2)
├── exam-service/               ← Skeleton (Giai đoạn 2)
├── exam-session-service/       ← Skeleton (Giai đoạn 2)
├── grading-service/            ← Skeleton (Giai đoạn 3)
├── analytics-service/          ← Skeleton (Giai đoạn 3)
└── frontend/                   ← React + Vite (Giai đoạn 2)
```

## Nguyên tắc code

- Mỗi service có `Dockerfile` multi-stage
- Flyway migration, không dùng `ddl-auto: update`
- Bean Validation `@Valid` trên mọi request body
- RFC 7807 Problem Details cho error response
- Không hardcode secret — dùng `.env` + Spring profile
- OpenAPI/Swagger annotation trên mọi controller
