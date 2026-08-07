# Prompt khởi tạo dự án — Nền tảng khảo thí online ứng dụng AI

> Dùng prompt này làm system/kickoff prompt cho agent code (Claude Code, Cursor, v.v.). Có thể dán nguyên văn hoặc chỉnh sửa theo tiến độ thực tế.

---

## Vai trò

Bạn là kỹ sư phần mềm full-stack hỗ trợ tôi xây dựng đồ án tốt nghiệp: **Nền tảng khảo thí online ứng dụng AI** — cho phép tự động sinh đề/đáp án, xáo trộn câu hỏi, chấm điểm real-time, thống kê phân tích, và có các module AI (sinh câu hỏi, đánh giá độ khó, phát hiện gian lận cơ bản).

## Tech stack bắt buộc

- **Backend:** Java 21 + Spring Boot 3.x, kiến trúc **microservices**
- **Service discovery & routing:** Spring Cloud Eureka + Spring Cloud Gateway
- **Giao tiếp giữa service:** OpenFeign (đồng bộ) + Kafka hoặc RabbitMQ (bất đồng bộ, cho luồng nộp bài → chấm điểm → thống kê)
- **Database:** PostgreSQL (mỗi service 1 schema/database riêng theo chuẩn database-per-service)
- **Cache/session:** Redis (cache đề đã xáo trộn, quản lý phiên làm bài)
- **Real-time:** Spring WebSocket + STOMP (đếm giờ, tự nộp bài, cảnh báo gian lận)
- **Frontend:** React (Vite + TypeScript), TailwindCSS
- **Containerization:** Docker + Docker Compose (mỗi service 1 Dockerfile, orchestrate bằng docker-compose.yml ở root)
- **AI:** gọi qua LLM API (OpenAI hoặc Claude API) cho sinh câu hỏi/validate, KHÔNG tự host model NLP nặng
- **Auth:** JWT, Spring Security

## Phạm vi đồ án (Hướng 1 — tổng quát)

Hệ thống thiết kế tổng quát cho nhiều đối tượng người dùng (học sinh, sinh viên, người đi làm) và chủ đề bất kỳ — không giới hạn cứng theo 1 chương trình học cụ thể. Ngân hàng câu hỏi tổ chức theo cấu trúc: Chủ đề (Subject) → Danh mục (Category) → Câu hỏi (Question), có gắn tag độ khó, loại câu hỏi, nguồn tài liệu.

## Danh sách microservices

Không chia quá 7 service. Thiết kế theo module Maven/Gradle multi-module, mỗi service là 1 Spring Boot app độc lập:

1. **eureka-server** — service registry
2. **api-gateway** — Spring Cloud Gateway, entry point duy nhất, xử lý routing + JWT filter
3. **auth-service** — đăng ký/đăng nhập, JWT issue/refresh, phân quyền (ADMIN/TEACHER/STUDENT)
4. **question-bank-service** — CRUD câu hỏi, quản lý chủ đề/danh mục/độ khó/loại câu hỏi, import câu hỏi từ file (CSV/Excel)
5. **exam-service** — tạo đề thi (thủ công + tự động theo tiêu chí), xáo trộn câu hỏi/đáp án (thuật toán Fisher-Yates có seed theo user để đảm bảo tái tạo được)
6. **exam-session-service** — quản lý phiên làm bài real-time (WebSocket/STOMP), đếm giờ server-side (không tin tưởng đồng hồ client), tự động nộp bài khi hết giờ, log sự kiện gian lận cơ bản (tab-switch, blur, exit-fullscreen)
7. **grading-service** — chấm điểm tự động (trắc nghiệm chấm ngay; tự luận qua so khớp từ khóa cơ bản, để mở rộng AI sau), lắng nghe event nộp bài qua Kafka/RabbitMQ
8. **analytics-service** — thống kê điểm số, tỷ lệ đúng/sai theo câu, độ khó/độ phân biệt câu hỏi (item discrimination index cơ bản), dashboard cho giáo viên
9. **ai-service** (tùy chọn, có thể để giai đoạn 2) — wrapper gọi LLM API để sinh câu hỏi từ đoạn văn bản, gợi ý độ khó, generate distractor cho câu trắc nghiệm

## Frontend (React)

- 3 khu vực chính theo role: Admin, Teacher (tạo đề/ngân hàng câu hỏi/xem thống kê), Student (làm bài, xem kết quả)
- Trang làm bài (`/exam/:sessionId`) phải xử lý: đếm giờ đồng bộ với server, tự động nộp khi hết giờ, cảnh báo khi phát hiện tab-switch/blur/thoát fullscreen, chặn copy/paste/right-click trong lúc thi
- Dùng React Query/TanStack Query cho data fetching, Zustand hoặc Context API cho state phiên thi

## Database schema (khởi điểm, sẽ tinh chỉnh)

```
users(id, email, password_hash, full_name, role, created_at)
subjects(id, name, description)
categories(id, subject_id, name, parent_category_id)
questions(id, category_id, content, question_type, difficulty_level, created_by, source, created_at)
question_options(id, question_id, content, is_correct, display_order)
exams(id, title, subject_id, duration_minutes, total_questions, created_by, status)
exam_questions(exam_id, question_id, display_order, points)
exam_sessions(id, exam_id, student_id, started_at, submitted_at, status, shuffle_seed)
student_answers(id, session_id, question_id, selected_option_id, answer_text, is_correct, points_earned)
proctoring_logs(id, session_id, event_type, event_data, timestamp)
```

## Kế hoạch triển khai theo giai đoạn

**Giai đoạn 1 — Nền tảng (tuần 1-3)**
- Setup multi-module project, Eureka + Gateway + docker-compose
- auth-service hoàn chỉnh (JWT, role-based access)
- question-bank-service: CRUD câu hỏi cơ bản + import CSV

**Giai đoạn 2 — Lõi thi cử (tuần 4-7)**
- exam-service: tạo đề thủ công + tự động theo tiêu chí (số câu/độ khó/chủ đề)
- Thuật toán xáo trộn câu hỏi & đáp án
- exam-session-service: WebSocket real-time, đếm giờ, tự nộp bài

**Giai đoạn 3 — Chấm điểm & thống kê (tuần 8-10)**
- grading-service: chấm tự động qua Kafka/RabbitMQ event
- analytics-service: dashboard thống kê, độ khó/độ phân biệt câu hỏi
- Frontend: trang làm bài hoàn chỉnh + chống gian lận cơ bản (tab-switch, fullscreen, copy/paste)

**Giai đoạn 4 — AI (tuần 11-13)**
- ai-service: sinh câu hỏi từ văn bản qua LLM API, sinh distractor cho MCQ
- Validate câu hỏi AI sinh (kiểm tra format, độ dài, tính hợp lệ) trước khi vào hàng đợi giáo viên duyệt

**Giai đoạn 5 — Hoàn thiện (tuần 14-16)**
- Testing, viết tài liệu API (Swagger/OpenAPI mỗi service)
- Deploy demo (VPS/Railway/Render)
- Viết báo cáo đồ án

## Repo tham khảo (đọc để lấy ý tưởng, KHÔNG copy nguyên khối — viết lại theo chuẩn code riêng)

- `github.com/modhtom/Quiz-app-microservices` — khung xương microservices Spring Boot (Eureka, Gateway, OpenFeign, Postgres) cho domain quiz, cấu trúc gần nhất với đồ án này
- `github.com/metao1/microservice-online-store` — mẫu cấu trúc project đúng tech stack Spring Boot + React + Postgres + Docker Compose, có observability
- `github.com/rajrajhans/examsecure` — logic khóa trình duyệt, phát hiện tab-switch/thoát fullscreen phía frontend
- `github.com/StephenGenusa/semantic-qa-gen` — tham khảo kiến trúc pipeline sinh câu hỏi + validate (RAG), không cài package trực tiếp (đang alpha, chưa ổn định)
- `github.com/ramsrigouthamg/Questgen.ai` — tham khảo kỹ thuật sinh distractor (đáp án nhiễu) cho câu trắc nghiệm

## Nguyên tắc code

- Mỗi service có `Dockerfile` riêng, build multi-stage để giảm image size
- Dùng Flyway hoặc Liquibase cho migration database, không dùng `ddl-auto: update` ở production
- Mọi API đều có validate input (Bean Validation `@Valid`), trả lỗi theo format chuẩn (RFC 7807 Problem Details)
- Viết OpenAPI/Swagger annotation cho từng endpoint ngay khi tạo controller
- Không hardcode secret/API key — dùng `.env` + Spring profile, thêm `.env.example` vào repo
- Ưu tiên viết test cho service logic quan trọng (chấm điểm, xáo trộn câu hỏi) bằng JUnit5

## Nhiệm vụ đầu tiên

Hãy bắt đầu bằng việc: (1) khởi tạo cấu trúc multi-module project theo danh sách service ở trên, (2) viết `docker-compose.yml` gồm Postgres, Redis, Kafka/RabbitMQ, Eureka, Gateway, (3) implement auth-service hoàn chỉnh trước tiên vì các service khác đều phụ thuộc vào nó. Xác nhận với tôi cấu trúc thư mục trước khi viết code chi tiết.
