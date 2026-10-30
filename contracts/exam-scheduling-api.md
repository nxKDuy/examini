# Exam Scheduling / Calendar — API hiện hành

Backend domain `92ddd2f62f74d5e1f37a7336ad93fdf084d38f6c`; bản áp độc lập
MAIN `59c10172378d8bba550feee0856b311be8ed602c`. Không kéo Browser Integrity
Phase 2 hoặc QB compatibility chưa tích hợp. Bản sửa HTTP timezone provider
`c5ac78f`, MAIN `58bbd76c4c2c953f39c18decac301b75243ef3ac` giữ nguyên offset
request trước validation. Business authority:
[Product/Domain Scope Lock](exam-scheduling-scope.md). Frontend Calendar/Notification đã tích hợp tại MAIN merge `08ec23a`,
follow-up lưới tháng/popup `35ea5cc` merge tại `035c586`;
UI/UX scope và manual UI đã được người dùng duyệt. Gateway API E2E 34/34,
frontend regression 83/83 và browser trên runtime thật PASS; push vật lý và
Gmail Inbox vẫn là follow-up riêng. Xem `exam-scheduling-result.md`.

## Teacher: đọc và chỉnh lịch

`GET /exams/{id}/schedule`: OWNER/MANAGER/PROCTOR có quyền đọc tài nguyên.
`PUT /exams/{id}/schedule`: OWNER/MANAGER, không PROCTOR hoặc Teacher không có
quyền tài nguyên. Access JWT qua Gateway; không tự tạo identity headers.

Request PUT là toàn bộ cấu hình lịch:

```json
{
  "startAt": "2030-01-02T08:00:00+07:00",
  "endAt": "2030-01-02T10:00:00+07:00",
  "timezone": "Asia/Ho_Chi_Minh",
  "deadlineRule": "CAPPED_AT_END",
  "autoOpen": true,
  "autoClose": true,
  "reminderOffsetsMinutes": [1440, 15],
  "expectedRevision": 0,
  "reason": "Điều chỉnh lịch thi"
}
```

startAt/endAt có thể null theo scope. Offset phải hợp lệ tại local time của
IANA timezone; DST gap bị từ chối, overlap phải chọn offset hợp lệ. Response
dùng UTC ISO Instant, giữ timezone Exam riêng. Đổi timezone không tự đổi Instant.
Response: examId, startAt, endAt, timezone, deadlineRule, autoOpen, autoClose,
reminderOffsetsMinutes, scheduleRevision, lifecycleRevision, serverNow.

- Lịch optional; FULL_DURATION không cần endAt. CAPPED_AT_END và
  REQUIRE_FULL_WINDOW cần endAt. autoOpen cần startAt; autoClose cần endAt.
- Offsets [] tắt nhắc; tối đa 10 số nguyên khác nhau, mỗi số 1..43200 phút.
- expectedRevision phải đúng; stale trả 409 SCHEDULE_REVISION_MISMATCH.
- DRAFT/PUBLISHED chỉnh khi chưa có durable Start. OPEN chỉ kéo dài endAt đang
  tồn tại; deadlineRule chỉ đổi khi chưa Start. CLOSED không chỉnh lịch.
- Thay đổi sau Publish cần reason. No-op không tăng revision. Start admission
  chưa giải quyết trả 409 START_ADMISSION_IN_PROGRESS; frontend cho thử lại.
- Thay đổi metadata của lịch đang tồn tại cũng phát RESCHEDULED, kể cả giờ giữ
  nguyên. Hai bên đều không lịch không phát sự kiện lịch giả.
- API create/update Exam cũ vẫn nhận LocalDateTime startTime/endTime, hiểu là
  UTC để tương thích. UI Scheduling dùng API schedule có offset ở trên.

## Calendar query cho cả hai role

`GET /exams/calendar?from=<UTC Instant>&to=<UTC Instant>&includeUnscheduled=false&page=0&size=100`

Range dương, tối đa 366 ngày; Page chuẩn Spring, size tối đa 100. Query theo
quyền tài nguyên ở backend: Teacher gồm Exam mình sở hữu/tham gia quản lý;
Student gồm assigned/joined/followed PUBLISHED/OPEN/CLOSED, không DRAFT. PUBLIC
chưa Join chỉ vào My Calendar khi user chủ động Follow. Follow không chiếm
participant/session capacity. includeUnscheduled=true dành cho Agenda; không
bịa ngày cho grid. Frontend phải xử lý pagination, không lấy một page rồi báo
đủ toàn bộ lịch.

CalendarItem:
examId, examTitle, status, startAt, endAt, timezone, durationMinutes, deadlineRule,
canJoin, canStart, canResume, reasonCode, attemptsRemaining, effectiveExpiresAt,
relation, followed, participant, participantCount, maxParticipants, serverNow,
calendarState, latestSessionId, latestSessionStatus.

participantCount/maxParticipants chỉ trả cho Teacher; latestSessionId/status
chỉ cho Student của chính mình. Calendar không chứa đáp án, join code, audit
reason hoặc dữ liệu Student khác. Teacher calendarState hiện dùng lifecycle;
Student calendarState phản ánh availability/attempt riêng.

## Student availability và PUBLIC Follow

- `GET /exams/{id}/availability?code=<optional>`: preview có quyền trước giờ.
- `GET /exams/code/{code}/preview`: code hợp lệ cho preview, không Join hoặc
  chiếm capacity; không dùng route detail cũ để đoán preview.
- `PUT /exams/{id}/follow` và `DELETE /exams/{id}/follow`: Student; response
  `{examId,followed,participant}`. Unfollow vẫn được khi đã CLOSED, không làm
  mất quyền assigned/joined.
- Join và Start hiện hữu phải chờ startAt dù Teacher manual Open sớm.
  endAt là biên exclusive: không nhận Start mới tại/qua endAt.

Availability gồm các field Calendar tương ứng, thêm canView/scheduleRevision.
Backend là authority của canStart/canJoin/canResume/reasonCode/serverNow/deadline.
Frontend có thể đếm thời gian hiển thị từ serverNow nhưng không cấp quyền bằng
client clock. latest session quá hạn được server chuyển AUTO_SUBMITTED.

reasonCode hiện hành: EXAM_NOT_PUBLISHED, EXAM_CLOSED, BEFORE_START_AT,
AFTER_END_AT, EXAM_NOT_OPEN, JOIN_REQUIRED, ATTEMPT_LIMIT, INSUFFICIENT_TIME,
CAPACITY_FULL, SNAPSHOT_UNAVAILABLE; null khi không có chặn. calendarState
Student ưu tiên IN_PROGRESS/SUBMITTED/AUTO_SUBMITTED/CANCELLED, rồi MISSED,
CLOSED, AVAILABLE, JOIN_REQUIRED, UPCOMING, NOT_OPEN hoặc reasonCode.
MISSED chỉ khi đã qua endAt mà chưa attempt; Close sớm không giả thành MISSED.

## Lifecycle và deadline

Publish/Open/Close giữ routes PUT hiện hữu; Close nhận optional `{reason}`,
reason bắt buộc khi đóng sớm trước endAt. `PUT /exams/{id}/reopen` cần reason,
OWNER/MANAGER, CLOSED và chưa endAt (hoặc không endAt), có frozen snapshot.
Manual Close không bị autoOpen mở lại. Scheduler poll theo clock, autoClose
PUBLISHED/OPEN, autoOpen PUBLISHED; downtime qua cả window không phát OPEN giả.

Session deadline cấp tại Start: FULL_DURATION = startedAt + duration;
CAPPED_AT_END = min(startedAt + duration, endAt); REQUIRE_FULL_WINDOW chỉ Start
khi đủ duration (equality được), deadline = startedAt + duration. Session đã
cấp giữ expiry/revision; extend lịch chỉ ảnh hưởng attempt mới.

## Nội bộ và Notification

Exam/Session Start admission reserve/claim/confirm/release dùng EXAM_INTERNAL_TOKEN
riêng qua X-Service-Token, không Bearer/Gateway. Confirm sau durable Session
commit; recovery dùng Session API, không query DB foreign domain. Failed Start
không khóa schedule vĩnh viễn. Browser không biết token hoặc gọi internal routes.

Recipient API/event/channel contract: [Scheduling Notification](notification-scheduling.md).
`submitted` theo attempt hiện hành đã user chốt A; expiry do server xác định.
Email/Auth contacts riêng, không đưa Gmail credentials vào frontend.

## Bằng chứng và gate còn lại

Worker PostgreSQL/RabbitMQ tests: Exam 24, Session 18, Grading 2 PASS trên branch.
Cây MAIN-based: Exam 15, Session 8, Grading 2 PASS. Một số client trong test là
mock; đây không là real cross-domain E2E. Flyway 10.10.0 bridge được worker kiểm
MAIN→R, Phase2→R, MAIN→R→Phase2 và full set; không sửa checksum migration cũ.
MAIN runtime và frontend/manual/browser gates được báo độc lập trong
`exam-scheduling-result.md`.

Sau sửa timezone: MAIN-based Exam có 19 executed PASS và 1 Outbox contract
skipped trong unit test; real Gateway E2E 34/34 PASS trên bảy service thật,
PostgreSQL/Redis/RabbitMQ/Mailpit. E2E kiểm +07:00, offset sai, DST gap/overlap,
cả ba deadline rules, auto/manual lifecycle, Follow, resume, grading/snapshot,
attempt hiện hành và in-app/SMTP. Chi tiết và đường dẫn evidence nằm trong
`exam-scheduling-coordination.md`. Pipeline Notification → Gmail đã có SMTP ACK thật (Inbox chưa verify),
Frontend Calendar/manual UI đã được duyệt và tích hợp. Browser push vật lý
chưa verify; không coi backend gate hay SMTP ACK là bằng chứng cho kênh này.

## Calendar sync (iCalendar) — CURRENT

| Route | Auth | Response |
| --- | --- | --- |
| `GET /exams/calendar/feed` | JWT (mọi vai trò) | `{active, createdAt, lastUsedAt}` |
| `POST /exams/calendar/feed` | JWT | 201 `{token, createdAt}`; tạo mới thay token cũ (link cũ 404) |
| `DELETE /exams/calendar/feed` | JWT | 204, tắt đồng bộ |
| `GET /exams/calendar/feed/{token}.ics` | **không JWT**; token 20–64 ký tự `[A-Za-z0-9_-]` là thông tin xác thực | `text/calendar` |
| `GET /exams/{id}/calendar.ics` | JWT, cùng quyền xem như `/availability` | `text/calendar`, tải về |

- DB chỉ lưu SHA-256 của token (`calendar_feeds`, migration V6). Feed chỉ đọc, cùng phạm vi như `GET /exams/calendar`
  (60 ngày trước → 300 ngày sau, tối đa 100 kỳ thi), bỏ kỳ thi chưa có lịch và lượt bị gỡ (REVOKED).
- Mỗi kỳ thi một VEVENT: `UID: exam-<id>@examini`, `SEQUENCE` = scheduleRevision, giờ UTC, `DTEND` = endAt hoặc
  startAt + thời lượng, VALARM theo `reminderOffsetsMinutes`. Link trong mô tả: phòng chờ (Student) hoặc trang quản lý.
- Gateway cho qua không cần JWT duy nhất mẫu `GET /exams/calendar/feed/<token>.ics` (vẫn xoá header danh tính giả);
  exam-service từ chối nếu kèm Bearer/X-Service-Token.
- Email lịch thi (Notification) đính kèm `lich-thi.ics` cùng UID/SEQUENCE cho EXAM_SCHEDULED, EXAM_RESCHEDULED, EXAM_REMINDER.
