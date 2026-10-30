# EXAMINI — Nền tảng khảo thí trực tuyến ứng dụng AI

**Đồ án tốt nghiệp** — Đại học Tôn Đức Thắng (TDTU)

EXAMINI cho phép giáo viên xây ngân hàng câu hỏi (nhập tay, nhập từ Word/PDF/ảnh, sinh bằng AI), tạo đề theo ma trận,
tổ chức kỳ thi có giám sát, chấm điểm tự động + chấm tay có AI gợi ý, và xem thống kê có AI diễn giải.

## Kiến trúc

```
                       ┌──────────────────────────── Docker network ─────────────────────────────┐
 Trình duyệt ──► nginx (frontend, :80) ──/api──► api-gateway :8080 ──► eureka-server :8761 (đăng ký dịch vụ)
   React SPA     · phục vụ SPA, cache asset      · xác thực JWT        │
                 · header bảo mật                · giới hạn tần suất   ├─► auth-service          :8081  auth_db
                                                 · CORS                ├─► organization-service  :8088  organization_db
                                                                       ├─► question-bank-service :8082  question_bank_db + volume question_media
                                                                       ├─► exam-service          :8083  exam_db
                                                                       ├─► exam-session-service  :8084  exam_session_db + volume answer_audio, camera_data
                                                                       ├─► grading-service       :8085  grading_db
                                                                       ├─► analytics-service     :8086  analytics_db
                                                                       └─► notification-service  :8087  notification_db (email SMTP, Web Push)
                       RabbitMQ: sự kiện exam.attempt.* / grading.result.* … giữa các service (outbox)
                       Redis: phiên làm bài, khoá, cache · PostgreSQL 16: mỗi service một database (Flyway)
                       Gemini / Ollama (tuỳ chọn): sinh câu hỏi, gợi ý chấm tự luận, đọc số liệu thống kê
                       └─────────────────────────────────────────────────────────────────────────┘
```

Giao kèo API và sự kiện giữa các service nằm trong [`contracts/`](contracts/).

| Thành phần | Công nghệ |
|---|---|
| Backend | Java 21, Spring Boot 3.3, Spring Cloud (Eureka, Gateway, OpenFeign) |
| Dữ liệu | PostgreSQL 16 (database-per-service, Flyway), Redis 7, RabbitMQ 3 |
| Bảo mật | JWT (access + refresh), Google Sign-In, phân quyền theo vai trò và theo tổ chức, token nội bộ riêng cho từng cặp service |
| Frontend | React 19, Vite, TypeScript, TanStack Query, biểu đồ SVG tự viết, PWA, song ngữ Việt/Anh |
| AI | Gemini (mặc định) → Ollama tự host → bộ sinh cục bộ không cần mạng; OCR Tesseract (vie+eng); nhận diện khuôn mặt chạy trên trình duyệt |
| Triển khai | Docker Compose, nginx, GitHub Actions CI |

## Tính năng theo vai trò

**Học sinh** — tham gia kỳ thi bằng mã/được giao/công khai; làm bài với 7 dạng câu hỏi (trắc nghiệm một đáp án, nhiều đáp án, đúng/sai,
đúng/sai theo chùm kiểu THPT 2025, trả lời ngắn, tự luận, nói — ghi âm), câu hỏi có thể kèm đoạn văn, ảnh, âm thanh; xem kết quả và lời giải;
trang **Tiến bộ** với biểu đồ điểm theo thời gian và gợi ý ôn tập do AI viết.

**Giáo viên** — ngân hàng câu hỏi nhiều kho, chia sẻ/đồng bộ; nhập từ Word/PDF/ảnh/Excel theo mẫu; sinh câu hỏi bằng AI theo
mức Bloom từ tài liệu; tạo đề từ ma trận/blueprint; in đề + phiếu trả lời; lịch thi, thông báo email/push; giám sát
(chống chuyển tab, camera); chấm tay có AI gợi ý; thống kê đề (độ khó, độ phân biệt) kèm nhận xét AI; xuất CSV/Moodle/Aiken.

**Tổ chức** — lớp/khoa/trường: mời thành viên, phân vai OWNER/ADMIN/MEMBER, quyền chấm, nhật ký; thêm cả tổ chức vào kỳ thi một lần.

**Quản trị** — quản lý tài khoản, trang **Thống kê hệ thống** (người dùng, kỳ thi, bài nộp theo ngày, phân bố câu hỏi).

## Chạy bằng Docker (khuyến nghị)

```bash
cp .env.example .env          # điền JWT_SECRET và các token nội bộ (openssl rand -base64 48)
                              # AUTH_BOOTSTRAP_EMAIL / AUTH_BOOTSTRAP_PASSWORD để tạo Admin đầu tiên
docker compose up -d --build  # lần đầu mất vài phút; đợi mọi container "healthy": docker compose ps
```

Mở **http://localhost** (hoặc `http://localhost:${WEB_PORT}` nếu đã đổi `WEB_PORT`). nginx chuyển `/api/*` tới gateway
nên trình duyệt chỉ cần một địa chỉ. Các cổng phụ trợ: Eureka `8761`, Gateway `8080`, RabbitMQ UI `15673`.

AI là tuỳ chọn: đặt `GEMINI_API_KEY`, hoặc chạy model tại chỗ `docker compose --profile ai up -d ollama` rồi đặt
`OLLAMA_URL=http://ollama:11434`. Không có cả hai thì các chức năng AI dùng bộ sinh/nhận xét theo luật.

## Phát triển cục bộ

```bash
docker compose up -d postgres redis rabbitmq eureka-server   # hạ tầng
mvn -pl auth-service spring-boot:run                          # chạy service đang sửa (tương tự cho service khác)
cd frontend && npm ci && npm run dev                          # http://localhost:3000, proxy API về gateway :8080
```

## Kiểm thử

```bash
mvn test                                  # toàn bộ backend (cần PostgreSQL local; xem .github/workflows/ci.yml)
cd frontend && npx tsc -b && npx oxlint && node --test tests/*.test.mjs && npm run build
```

CI (GitHub Actions) chạy test backend với PostgreSQL/Redis/Mailpit, kiểm tra kiểu + lint + test + build frontend, và build toàn bộ image Docker.

## Vận hành

- **Sao lưu:** `./scripts/backup.sh` → `backups/<thời gian>/` (mọi database + file đính kèm, giữ 14 bản).
  Khôi phục: `./scripts/backup.sh --restore backups/<thời gian>`.
- **Admin đầu tiên:** `AUTH_BOOTSTRAP_EMAIL` + `AUTH_BOOTSTRAP_PASSWORD` (≥ 16 ký tự). Bootstrap không nâng quyền
  tài khoản học sinh/giáo viên trùng email; sau khi tạo xong có thể xoá khỏi `.env`.
- **Giới hạn tần suất (gateway, theo địa chỉ IP):** quên/đặt lại mật khẩu 20/phút, đăng nhập/đăng ký 300/phút,
  gọi AI 60/phút, tải file 600/phút — đủ rộng cho cả lớp dùng chung một IP trường, vẫn chặn được dò mật khẩu/spam hàng loạt
  (khoá theo tài khoản vẫn do auth-service đảm nhận). Vượt ngưỡng trả `429` kèm `Retry-After`. Chỉnh bằng biến
  `GATEWAY_RATE_LIMIT_SIGN_IN_PER_MINUTE`… hoặc tắt bằng `GATEWAY_RATE_LIMIT_ENABLED=false`.
- **Ảnh camera giám sát** tự xoá sau `CAMERA_RETENTION_DAYS` ngày kể từ hạn thi.
- **Production:** đặt `APP_BASE_URL` đúng tên miền, cấu hình SMTP, `AUTH_RESET_LOG_LINKS=false`, đặt HTTPS phía trước nginx.

## Cấu trúc thư mục

```
├── docker-compose.yml         toàn bộ hệ thống (14 container)
├── .env.example               biến môi trường (không commit .env)
├── pom.xml                    Maven multi-module
├── contracts/                 giao kèo API/sự kiện giữa các service
├── docker/postgres/           script tạo database
├── scripts/                   backup.sh (sao lưu/khôi phục)
├── eureka-server/  api-gateway/
├── auth-service/  organization-service/  question-bank-service/
├── exam-service/  exam-session-service/  grading-service/
├── analytics-service/  notification-service/
└── frontend/                  React SPA + Dockerfile/nginx
```

## Nguyên tắc code

- Mỗi service có `Dockerfile` multi-stage, healthcheck qua `/actuator/health`.
- Flyway migration, không dùng `ddl-auto: update`; mỗi service chỉ đọc database của mình.
- Bean Validation trên mọi request body; lỗi trả về RFC 7807 Problem Details.
- Không hardcode secret — mọi khoá lấy từ `.env`; token nội bộ khác nhau cho từng kênh service-to-service.
