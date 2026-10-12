# Browser Integrity Phase 2 — contract backend cho frontend/app

## Nguyên tắc

Event là tín hiệu do client báo cáo, không phải kết luận gian lận. Không có auto-fail,
auto-submit, thay đổi điểm, risk score, webcam hoặc AI. Grading, submission event và
Exam lifecycle giữ nguyên. `ExamAudit` ghi hành động quản trị; `IntegrityEvent` ghi
evidence theo attempt và không dùng chung bảng.

## IntegrityPolicy

Exam Service sở hữu `exams.integrity_policy` (JSONB). Chính sách mặc định cho cả
Exam cũ và mới tắt tất cả monitor, `LOG_ONLY`. OWNER/MANAGER được PUT **chỉ trong
DRAFT**; PROCTOR chỉ đọc. PUBLISHED/OPEN/CLOSED không sửa policy. Không tự suy diễn
policy mới từ các flag legacy `blockTabSwitch` / `requireFullscreen` / camera.

```json
{
  "monitorTabSwitch": true,
  "monitorWindowBlur": true,
  "requireFullscreen": true,
  "monitorCopy": true,
  "monitorPaste": true,
  "monitorNetwork": true,
  "warningPolicy": "WARN_ONLY"
}
```

`warningPolicy`: `LOG_ONLY | WARN_ONLY`, bắt buộc. PUT thay toàn bộ policy;
boolean vắng mặt mặc định false. `requireFullscreen` là yêu cầu hướng dẫn/ghi nhận,
không phải xác minh browser đã fullscreen trên server, và không khóa submit.

- `GET /exams/{examId}/integrity/policy`: OWNER/MANAGER/PROCTOR hoặc Participant đã
  đăng ký, đều phải có access JWT.
- `PUT /exams/{examId}/integrity/policy`: OWNER/MANAGER, DRAFT; tạo audit
  `INTEGRITY_POLICY_UPDATED` cùng actor/time/metadata.
- `ExamDto.Response` có thêm `integrityPolicy`; các field cũ giữ nguyên.
- Existing service-auth `POST /exams/{examId}/internal/start` trả policy trong
  response có sẵn. Session Service dùng consumer DTO riêng, không import entity
  hoặc đọc DB Exam. Không thêm service token lane hay thay Gateway.
- Khi tạo attempt, Session Service chép policy vào
  `exam_sessions.integrity_policy`. Policy của attempt không đổi khi resume.
  Provider cũ thiếu field này thì default disabled để bảo toàn Phase 1.
- `GET /sessions/{sessionId}/integrity/policy`: chủ Session hoặc staff của Exam.
  Frontend có thể gọi riêng; không cần thay shape StartSessionResponse.

## Event types và metadata

| Type | Policy bật | Metadata tùy chọn |
| --- | --- | --- |
| TAB_SWITCH | monitorTabSwitch | visibilityState, durationMs |
| WINDOW_BLUR, WINDOW_FOCUS | monitorWindowBlur | visibilityState, durationMs |
| EXIT_FULLSCREEN, FULLSCREEN_ENTER | requireFullscreen | fromFullscreen, toFullscreen, durationMs |
| COPY | monitorCopy | không có field metadata |
| PASTE | monitorPaste | không có field metadata |
| NETWORK_DISCONNECT, NETWORK_RECONNECT | monitorNetwork | networkState, durationMs |
| PAGE_RELOAD, SESSION_RESUME | ít nhất một monitor của attempt đang bật | không có field metadata |

`visibilityState`: `HIDDEN | VISIBLE`; `networkState`: `OFFLINE | ONLINE`.
`fromFullscreen`/`toFullscreen` boolean; `durationMs` integer 0..86400000.
Metadata có thể null hoặc `{}`. Field không biết, field không phù hợp type,
networkState trái với disconnect/reconnect và fullscreen target trái type bị 400.
Không có clipboard text, URL, page content, answer, JWT, password, fingerprint,
keystroke, history hoặc screen capture. Không log body lỗi.

## Ingestion

`POST /sessions/{sessionId}/integrity/events`, Content-Type `application/json`.
Access JWT của chủ Session bắt buộc; backend tự lấy actor từ chữ ký JWT và
examId/userId qua Session. Không thể gửi cho Session người khác. Teacher/Admin
đang tham gia Exam theo Phase 1 PUBLIC cũng có thể gửi cho chính attempt của mình;
persona không tự cấp quyền staff review. Unknown field ở envelope hoặc metadata bị 400.

```json
{
  "clientEventId": "00000000-0000-4000-8000-000000000001",
  "type": "NETWORK_RECONNECT",
  "occurredAt": "2026-10-05T14:40:00Z",
  "metadata": {"networkState": "ONLINE", "durationMs": 12000}
}
```

`clientEventId` UUID và `type` bắt buộc. `occurredAt` ISO instant tùy chọn, chỉ là
client evidence; normalize xuống microseconds phù hợp PostgreSQL, chấp nhận
trong ±7 ngày so với server receipt để hỗ trợ clock skew/buffer. Không dùng client
clock cho state, rate limit, severity, duration của Exam hoặc quyền truy cập.
`receivedAt` luôn do server ghi. NETWORK_DISCONNECT có thể gửi khi reconnect
nếu Session vẫn IN_PROGRESS và serverNow < expiresAt. Evidence mới sau submit/
expiry bị từ chối; chưa có late-terminal upload window.

Response 200 cho cả lần đầu và exact retry:

```text
Receipt:
  event: {
    id, clientEventId, examId, sessionId, userId,
    type, occurredAt?, receivedAt, severity, metadata?, source
  }
  duplicate: boolean
  warningRecommended: boolean
  warning: nullable string
```

`source` server gán `BROWSER`: nguồn khai báo, không chứng minh event thật.
Severity do server gán: EXIT_FULLSCREEN = MEDIUM, còn lại LOW; chỉ dùng display/
filter, không quyết định guilt hay điểm. WARN_ONLY chỉ khuyến nghị warning cho
TAB_SWITCH/WINDOW_BLUR/EXIT_FULLSCREEN/COPY/PASTE. LOG_ONLY, network và recovery
signals không trả warningRecommended. Câu warning nêu rõ signal không phải proof.

## Retry, rate và lỗi

- DB unique `(session_id, client_event_id)`. Hai requests đồng thời cùng ID tạo
  đúng một event; khác attempts có thể dùng cùng clientEventId.
- Exact retry so type/occurredAt/metadata, trả cùng event ID và server receipt.
  Cùng ID nhưng payload khác trả 409. `null` metadata khác `{}`: retry giữ nguyên
  payload. Exact retry sau submit/expiry vẫn trả event cũ cho chủ Session.
- Pessimistic Session row lock + fresh entity trước state/rate check đảm bảo
  dedup/rate-limit atomic qua nhiều instance. Transaction ngắn, không remote call
  trong ingest; event không thay đổi Session state, answer, outbox hay grading.
- Default `INTEGRITY_EVENTS_PER_MINUTE=120`: rolling 60s theo receivedAt.
  `INTEGRITY_MAX_EVENTS_PER_SESSION=10000`: trần evidence cho một attempt.
  Cấu hình tập trung service-local, bắt buộc >0. Retry không chiếm thêm quota.
- Body tối đa 4096 bytes, kể cả chunked, đọc có giới hạn trước parse JSON.
- 400: UUID/type/JSON/metadata/time không hợp lệ, payload trailing JSON, duplicate
  JSON key, enum ordinal và fractional durationMs.
- 401: JWT thiếu/sai/refresh/duplicate auth header, hoặc dùng internal service
  token vào API public.
- 403: foreign Session, Student xem private evidence,
  staff không thuộc Exam; Teacher persona/ADMIN không có quyền xem mọi Exam.
- 404: Session/Exam không tồn tại.
- 409: policy đóng băng, monitor tắt, Session hết giờ/terminal, ID collision.
- 413: body >4096 bytes; 415: Content-Type không phù hợp.
- 429: rate/total ceiling; frontend retry có backoff, không block answer/submit.
- 503: permission provider/config không sẵn sàng khi review; fail closed.
  Permission provider trả role rỗng/không hợp lệ cũng bị 403.

Integrity là request độc lập. Core start/save/submit không gọi IntegrityService,
không gửi integrity MQ message, không đợi event ingest. Lỗi integrity không biến
submission thành thất bại. Outage toàn bộ Session DB vẫn là outage core như trước.

## Private review và summary

Tất cả routes review yêu cầu TEACHER/ADMIN access JWT **và** permission public
contract từ Exam Service trả OWNER/MANAGER/PROCTOR của đúng Exam. Student chỉ đọc
policy của mình; không đọc private event/summary.

- `GET /sessions/exam/{examId}/integrity/events`
- `GET /sessions/{sessionId}/integrity/events`
- `GET /sessions/{sessionId}/integrity/summary`

Event query nhận `type?`, `page=0`, `size=20` (1..100), page >=0; sort cố định
`receivedAt ASC, id ASC`. Response Spring Page có content, totalElements,
totalPages, number, size như convention hiện tại. Session query bound qua
session.examId; không nhận client-supplied exam identity.

```text
Summary: {examId, sessionId, totalEvents, counts: {
  TAB_SWITCH, WINDOW_BLUR, EXIT_FULLSCREEN, COPY, PASTE,
  NETWORK_DISCONNECT, NETWORK_RECONNECT, FULLSCREEN_ENTER, WINDOW_FOCUS,
  PAGE_RELOAD, SESSION_RESUME
}}
```

Mọi type luôn có count, kể cả 0. Thống kê SQL deterministic; không phải risk score.
Review polling là realtime foundation. Không đưa evidence vào broker WebSocket
legacy hiện tại vì chưa có topic authorization. Không thêm topic/subscription.

## Persistence và rollout

- Exam V4 `browser_integrity_policy`: JSONB policy/default disabled.
- Session V3 `browser_integrity`: attempt policy; `integrity_events` riêng,
  composite local FK `(session_id, exam_id, user_id)` đảm bảo identity binding.
- Unique retry key; type/severity/source CHECK; indexes session+receivedAt+id,
  exam+receivedAt+id, exam+type+receivedAt và occurredAt.
- Event entity không có setters, @Immutable, field updatable=false. DB trigger
  cấm UPDATE. Không có API sửa/xóa event; DBA retention chưa được triển khai.
- Migration không terminate active Session, không sửa timestamps/status/answers.
  Exam/Session deployment additive, giữ compatible old consumer/new provider.
  Provider cũ thiếu policy được Session mới coi disabled.
- MAIN review privacy/retention, migration, contract trước merge. Frontend Phase 2
  còn là task riêng; hiện tại chưa có browser collection UI.

## Kiểm thử tái lập

Maven verify cần PostgreSQL riêng với exam_test/session_test/grading_test. Test
classes dùng URL localhost:5432, user postgres, password rỗng; chỉ dùng disposable
DB, không chạy vào DB production. Ví dụ task này dùng container trust không mở
host port, Maven cùng namespace mạng:

```sh
docker run --rm --network container:examini-et-p2-postgres \
  -v /Users/mac/orca/workspaces/DATN/exam-taking-domain:/workspace \
  -v examini-maven-cache:/root/.m2 -w /workspace maven:3.9-eclipse-temurin-21 \
  mvn -q -pl exam-service,exam-session-service,grading-service,api-gateway -am verify
python3 exam-session-service/src/test/phase2_migration_check.py examini-et-p2-postgres
python3 exam-session-service/src/test/run_phase2_http.py
git diff --check
```

HTTP runner tự tạo container PostgreSQL/RabbitMQ/provider fixture và ba JVM
service độc lập, không mở host port, credential tổng hợp trong TemporaryDirectory
quyền hạn chế, cleanup chỉ tài nguyên đã tự tạo. Cần Docker images postgres:16-alpine,
rabbitmq:3.13-alpine, python:3.12-alpine, maven:3.9-eclipse-temurin-21 và JAR đã build.
Provider fixture là test contract, không phải claim đã dùng Question Bank runtime
production. HTTP test dùng HTTP thật giữa các JVM/Feign, local databases và MQ.

## Budget accounting (scope A, 2026-10-12)

Authoritative rules: `contracts/browser-integrity.md`. Each row stores `counted`,
`not_counted_reason` (NOT_BUDGET_SIGNAL | COALESCED | STALE), `coalesced_into` and
`effective_at` (migration V4; existing rows: network signals become NOT_BUDGET_SIGNAL).
