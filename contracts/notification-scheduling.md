# Scheduling Notification — contract đã review

Notification implementation: `aa323710410469da676ab0f1a14423953f57afed`,
đã tích hợp MAIN tại `13b5f28`. Auth contact API đã tích hợp; Exam producer và
recipient API bên dưới là contract được duyệt để triển khai, **chưa có trong
MAIN** ở checkpoint này. Release E2E và frontend vẫn chờ các gate tương ứng.
Product/Domain authority: [scope locks](exam-scheduling-scope.md).

## Event transport

Topic durable `examini.events`, routing key bằng `eventType`; consumer queue
`notification.events.queue`, DLQ `notification.events.dlq`. Envelope v1:
`eventId: UUID`, `eventType`, `eventVersion: 1` (JSON integer),
`occurredAt: UTC ISO Instant`, `payload: object`. Producer transactional outbox,
publisher confirms, stable eventId khi retry. Không dùng chung queue Grading.

| Event | Payload bắt buộc |
| --- | --- |
| `exam.schedule.changed` | examId, examTitle, scheduleRevision >= 1, changeType SCHEDULED/RESCHEDULED/UNSCHEDULED, startAt, endAt, timezone, deadlineRule, reminderOffsetsMinutes, examStatus, actorUserId |
| `exam.lifecycle.changed` | examId, examTitle, status PUBLISHED/OPEN/CLOSED, trigger AUTO/MANUAL, actorUserId, lifecycleRevision >= 1 |
| `exam.recipients.changed` | examId, recipientRevision >= 1, cause ASSIGNED/UNASSIGNED/JOINED/MEMBER_ADDED/MEMBER_REMOVED/FOLLOWED/UNFOLLOWED, actorUserId |

Ba revision độc lập, không so sánh chéo. startAt/endAt/actorUserId phải hiện diện
nhưng được null; MANUAL lifecycle cần actor. UNSCHEDULED cần cả hai giờ null;
SCHEDULED/RESCHEDULED có ít nhất một giờ. Nếu đủ hai thì end > start. Timezone
IANA, deadlineRule FULL_DURATION/CAPPED_AT_END/REQUIRE_FULL_WINDOW. Offsets là
array tối đa 10 số nguyên khác nhau trong 1..43200 phút, [] tắt reminder.
Payload không chứa PII, recipient list, câu hỏi, keys, code, điểm, audit reason.

Chuỗi producer được MAIN duyệt: Draft create/update lịch phát schedule.changed;
Publish phát lifecycle.changed(PUBLISHED). Consumer đọc snapshot để lập reminder
và notice Student đầu tiên; dedup theo examId/scheduleRevision. Consumer vẫn
chấp nhận chuỗi cũ phát cả hai sự kiện lúc Publish. Recipients event chỉ cập nhật
reminder authority, không tạo notice Join/Follow/assignment mới.

## Provider Exam — REQUIRED, chưa MAIN-integrated

`GET /exams/{id}/internal/notification-recipients` chỉ dùng dedicated
`EXAM_NOTIFICATION_TOKEN` qua `X-Service-Token`, không Bearer. Gateway chặn
internal routes; Notification gọi mạng dịch vụ, không query DB Exam/Session.

Snapshot 200: examId, examTitle, status, scheduleRevision, lifecycleRevision,
recipientRevision, startAt, endAt, timezone, deadlineRule, reminderOffsetsMinutes,
recipients `[{userId, relation, started, submitted}]`.
Relation OWNER > MANAGER > ASSIGNED > JOINED > FOLLOWER, dedup một user;
không PROCTOR. DRAFT có quản lý nhưng không Student. 404 hủy job; 401/403 hoặc
provider unavailable không giả thành thành công. Email lấy từ
[Auth contact API](notification-contact-api.md), không từ event/DB copy.

User chốt A ngày 2026-10-07: `started` phản ánh từng có durable attempt;
`submitted=true` chỉ khi không còn attempt IN_PROGRESS và attempt mới nhất
đã SUBMITTED/AUTO_SUBMITTED. Student đang làm lượt 2 vẫn nhận Close/extend
dù đã nộp lượt 1. Provider xác định expiry theo server trước khi trả trạng thái.

## Ma trận gửi và reminder

- In-app/push mọi event Scheduling; email chỉ schedule/reschedule/unschedule/
  reminder, không Open/Close. Thiếu push subscription: SKIPPED, không email bù.
- OWNER/MANAGER và assigned/joined/followed, không broadcast mọi PUBLIC Student.
  Manual actor không nhận notice của mình; auto/reminder vẫn gửi managers.
- Student không nhận DRAFT. Open gửi Student liên quan; Close/đổi lịch chỉ tới
  Student chưa nộp theo scope và các managers. Bỏ cờ không mất quyền assigned/joined.
- Mặc định 24h/15m; chỉ lập mốc còn tương lai, không catch-up. Job trễ chỉ gửi
  trước startAt và đúng revision. Close/unschedule/removed recipient hủy;
  Reopen lập mốc còn tương lai. Reminder ghi giờ tuyệt đối theo timezone Exam,
  không ghi countdown bằng offset đã có thể hết hạn.
- Nội dung tiếng Việt; không keys/code/điểm/Student khác/private reason.

## Browser Push API — CURRENT

Mọi route cần access JWT, subject sở hữu subscription; forwarded headers không
cấp identity. Public key là tên tài nguyên, **endpoint vẫn cần đăng nhập**.

| Route | Request / response |
| --- | --- |
| GET /notifications/push/public-key | `{enabled, publicKey}`; chỉ public VAPID key |
| POST /notifications/push/subscriptions | PushSubscription.toJSON(): `{endpoint, keys:{p256dh,auth}, expirationTime?}` → 201 `{id,createdAt}` |
| DELETE /notifications/push/subscriptions/{id} | Owner-only, idempotent 204; foreign/unknown 404 |

Tối đa 10 subscriptions active/account, endpoint chuyển ownership khi account
khác đăng ký cùng browser. Pending send kiểm tra lại ownership. HTTPS provider
allowlist, không redirect/IP/localhost/userinfo. 404/410 provider revoke endpoint;
retry riêng từng endpoint, không resend tất cả sau thành công một phần.
Frontend phải hỏi/chốt opt-in, permission, service worker và account switching;
chưa có UI tương ứng trong MAIN.

## Persistence, cấu hình và giới hạn bằng chứng

PostgreSQL V1 inbox; V2 additive jobs/state/subscriptions/deliveries. Claim jobs
bằng SKIP LOCKED + lease, fan-out inbox/delivery/DONE cùng transaction. Revision,
recipient và expiry kiểm tra lại trước send/retry; thiếu config fail closed.
Job provider errors retry đến expiry; channel retries giới hạn có thể FAILED,
EXPIRED hoặc SKIPPED, không đánh dấu SENT khi chưa được provider chấp nhận.

In-app logical dedup được đảm bảo bằng unique keys. SMTP/push physical delivery
là at-least-once: mất ACK có thể gửi trùng. SMTP accepted không chứng minh vào
inbox; push accepted không chứng minh browser hiển thị. Cross-service snapshot
check không phải distributed transaction; còn khoảng nhỏ sau check/trước send.

Compose/env cần SMTP_*, hai dedicated tokens, VAPID_* và APP_BASE_URL đúng UI.
Secrets chỉ local env, không Git/log/handoff. Không expose service downstream
port. Init notification_db chỉ chạy với PostgreSQL volume mới; volume hiện hữu
cần tạo DB riêng trước startup, không reset volume.

Evidence component: archive aa32371, 52/52 tests trên PostgreSQL 16/RabbitMQ 3.13/
Mailpit; RFC8291 vector, ES256, independent decrypt, ownership/SSRF/retry/dedup/
expiry và late wording. **Chưa claim producer Exam E2E, Gmail inbox hay browser
push thực**, đây là release gates còn phải xác minh.

### MAIN runtime checkpoint ngày 2026-10-07

Image Notification từ committed MAIN `f5067a8` build thành công. Harness riêng
chạy PostgreSQL 16, Redis 7, RabbitMQ 3.13 và Auth/Gateway/Notification thật trên
network/database sạch: **43/43 PASS**. Auth V1–V4 và Notification V1–V2 migrate
thành công. API qua Gateway kiểm tra access JWT, refresh-token rejection,
identity spoof rejection, subscription ownership/transfer/revoke và SSRF guard.
Public-key endpoint cần đăng nhập; thiếu VAPID trả `enabled=false`.

Đây là kiểm tra API thành phần, **không** là Scheduling E2E: chưa producer Exam,
chưa email gửi thực, chưa browser subscription/display. Push subscription dùng
key P-256 được tạo cho kiểm tra API; không có push provider nào được gọi.
Harness/result local tại `/tmp/examini-scheduling-notification-api-runtime.py`
và `/tmp/examini-scheduling-notification-api-runtime-result.json`; credentials
test sinh ngẫu nhiên, không ghi vào report. Container/network của harness được
dọn sau test; stack và worktree hiện hữu giữ nguyên.
