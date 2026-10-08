# EXAM SCHEDULING & CALENDAR — END-TO-END RESULT

Ngày: 2026-10-07. Kết luận: **SCHEDULING FEATURE INTEGRATED WITH FOLLOW-UPS**.
Người dùng đã trả lời “Duyệt UI để MAIN merge” cho bản chạy thật `30cb232`.
Không push remote hoặc bắt đầu feature tiếp theo.

## Product Scope Lock

Nguồn: [Scope sản phẩm/domain](exam-scheduling-scope.md).

- Lịch không bắt buộc; start-only, end-only, cả hai hoặc không lịch.
- Manual và auto Open/Close kết hợp; hai cờ độc lập. Preview trước giờ được
  phép theo access rule; Join/Start phải chờ startAt, manual Open không bypass.
- Giáo viên chọn đủ ba rule: FULL_DURATION, CAPPED_AT_END, REQUIRE_FULL_WINDOW.
- OWNER/MANAGER quản lý, PROCTOR chỉ đọc; TEACHER != ADMIN.
- Timezone theo Exam, lưu UTC Instant; IANA/DST được kiểm ở backend.
- Ban đầu Teacher/Student có Agenda/Tháng/Tuần/Ngày; user sau đó trực tiếp
  đổi `/calendar` thành một lưới tháng + popup ngày cho cả hai role. PUBLIC chưa Join chỉ vào
  My Calendar khi Student chủ động Follow; không chiếm Participant/capacity.
- Notification gồm in-app/email/browser push, recipients/mốc nhắc theo scope
  đã duyệt; mặc định 24h/15m, tối đa 10 mốc 1..43200 phút, có thể tắt.
- Không recurrence, accommodation, Organization management, Google Calendar,
  nguồn câu hỏi/quality, AI scheduling hay Browser Integrity mới trong task.

## Backend

| Thành phần | Worktree/branch | Commit và kết quả |
| --- | --- | --- |
| Exam/Session | feature/exam-taking-domain | Worker `92ddd2f`, áp độc lập MAIN `59c10172378d8bba550feee0856b311be8ed602c`; không merge toàn Phase2 |
| HTTP timezone | feature/exam-taking-domain | Provider `c5ac78f`, MAIN `58bbd76c4c2c953f39c18decac301b75243ef3ac` |
| Notification | feature/notification | `aa323710410469da676ab0f1a14423953f57afed`, MAIN merge `13b5f28` |
| Auth contact | auth-notification-contact | `4b0f902`, MAIN merge `8fc83ad` |
| Gateway | MAIN/shared | `035a0e4`: internal isolation, strip service headers, 10 tests PASS |

Exam có scheduleRevision/lifecycleRevision, deadlineRule, UTC startAt/endAt,
IANA timezone, autoOpen/autoClose và reminderOffsetsMinutes. Availability và
CalendarItem phản ánh quyền/attempt riêng, không để frontend suy ra canStart.

API chính: GET/PUT `/exams/{id}/schedule`, GET `/exams/calendar`, GET
`/exams/{id}/availability`, GET `/exams/code/{code}/preview`, PUT/DELETE
`/exams/{id}/follow`, PUT reopen và các lifecycle API hiện hữu. Recipient/contact
APIs dùng service credentials riêng; không truy vấn DB foreign domain.

Migrations dùng bridge repeatable tương thích MAIN/Phase2, không sửa checksum
cũ hoặc reset DB. JDK21/PostgreSQL thật: MAIN Exam 19 executed PASS + 1 outbox
unit test skipped có lý do; Session 8, Grading 2 PASS trước merge Frontend.
Notification 52/52 PASS trên PostgreSQL/RabbitMQ/Mailpit; real outbox/broker đã
được kiểm thêm bằng unified API E2E, không gọi skipped unit test là đã chạy.

## Domain Scope Lock

- Window `[startAt,endAt)`: endAt luôn chặn Start mới, kể cả autoClose=false.
- FULL_DURATION: startedAt + duration. CAPPED_AT_END: min(full, endAt).
  REQUIRE_FULL_WINDOW: chỉ Start khi còn đủ duration, equality được phép.
- Session đã cấp giữ expiry/revision; kéo dài lịch không đổi deadline cũ,
  attempt mới lấy revision mới. Failed Start không khóa rule vĩnh viễn.
- DRAFT/PUBLISHED được chỉnh trước durable Start; OPEN chỉ extend endAt đang
  có, rule chỉ đổi trước Start; CLOSED không sửa. Close thủ công không bị auto
  Open đảo lại; Reopen trước endAt hoặc không endAt, có reason.
- Post-publish reschedule/early Close/Reopen có reason và audit old/new/actor.
- UTC/offset/DST gap/overlap: không silently đổi giờ; metadata editor cũ giữ
  nguyên Instant. Lượt 2 IN_PROGRESS vẫn nhận Close/extend dù lượt 1 đã submitted.

## Frontend

Worktree: `/Users/mac/orca/workspaces/DATN/app`, branch `frontend/app`.
Candidate đầu sạch: `30cb232dcf3bf500f91ec99daa254429100378f6`; follow-up
`35ea5cc4bd1092da24f31f3b6fea7cfa241762ae` đổi lịch tháng/popup theo yêu cầu
trực tiếp của user, đã review riêng và merge sau approval được xác minh qua terminal.
MAIN merge `--no-ff` đầu: `08ec23a1ecffdbd372438f6cccb905d40de87595`;
follow-up lịch tháng: `035c5862980157a01a50e9e9b27d2b760f217c0c`.
Frontend tree MAIN bằng candidate đã duyệt, giữ cả Product UI trước Scheduling.
Report domain: [EXAM_CALENDAR_RESULT](../frontend/EXAM_CALENDAR_RESULT.md).

- Teacher Calendar, schedule editor, timezone/rules/nhắc, reason/revision,
  lifecycle actions và panel quyền tài nguyên.
- Student Calendar, Today/Upcoming, Follow, countdown/backend availability,
  Lobby/Start/Resume/Result; mã Join chỉ preview trước giờ.
- Bản cuối: lưới tháng desktop, mobile chấm trạng thái và popup theo ngày; keyboard/focus/dialog
  lồng nhau, trạng thái bằng chữ/icon, không chỉ màu.
- Shared auth/API runtime; không manufacture identity headers. Enable,
  reconcile và disable push dùng chung hàng đợi/guard phiên/token/subscription.

## UI/UX Scope Lock

Nguồn: [Scope giao diện đã duyệt](exam-calendar-ui-scope.md).
`/calendar` ban đầu Agenda + bốn view; amendment user trực tiếp đổi thành một
lưới tháng, popup ngày và danh sách chưa có lịch bên dưới. Dashboard Today/Upcoming; grid theo
viewer timezone, thẻ/form theo Exam timezone, thêm Giờ của bạn khi khác.
Panel desktop/bottom sheet mobile; ngày mở popup list, Exam mở panel; không
drag/drop/resize/create từ ô trống.
Chip nhắc + custom/tắt, confirmation và reload khi stale revision. Bell 10 mới
nhất, unread mỗi 60s/focus; trang Notification pagination/read one/read all.
Push chỉ xin permission sau Bật, có blocked/unsupported/unconfigured states.

## Notification integration

Exam outbox → RabbitMQ → Notification → authority recipients/Auth contacts
→ in-app/SMTP PASS. PUBLIC Follow được đưa vào recipients; không public
broadcast, không gửi bù reminder đã qua. SMTP chỉ Scheduled/Rescheduled/
Unscheduled/Reminder; Open/Close không email. Active attempt là authority.

Gmail pipeline thật: 5/5 checks, đúng một logical email tới chính sender người
 dùng cung cấp, SMTP ACK và persisted SENT. **Chưa xác minh Gmail Inbox**.
Không gửi fixture addresses qua Gmail. Physical browser push
subscription/provider/OS display **chưa xác minh** do Chrome automation từ chối
permission; không fake delivery. Endpoint/crypto/retry/expiry và account cleanup
được phủ riêng bằng component/unit tests, không tương đương physical push E2E.

## MAIN Review

Backend PASS; Frontend technical review PASS; user manual UI approval YES.
Các fix chính: HTTP offset, DST axis/labels, start-only Agenda, early Join,
legacy metadata, nested focus, boundary refetch và account-safe push races.
Không feature domain mới do MAIN tự triển khai; MAIN chỉ tích hợp, review,
shared runtime/config/docs. Branch merge không conflict; stash restore có ba
modify/delete conflict test files, đã giữ cả bản committed và deletion của user.

## Runtime mặc định và lỗi start.bat/start.sh

Ban đầu 3000 phục vụ MAIN/frontend cũ; app/frontend tại 13100 chưa merge.
Sau user approval, MAIN đã chứa đúng UI. Docker Gateway8080 cũng còn bản cũ
(Calendar 400 parse UUID; Notification 404), đã rebuild/recreate bản hiện hành.

Commit riêng `ae7b2db` sửa hai startup scripts và SQL idempotent:

- Chạy frontend của checkout hiện hành với `--port 3000 --strictPort`.
- Chờ Compose health; tạo notification_db còn thiếu trên volume cũ, không xóa
  volume/dữ liệu; SQL chạy lại không tạo/ghi đè DB đã có.
- Docker Desktop macOS PATH fallback; giữ tài khoản test dev hiện có.
- `bash -n start.sh`/chạy `bash start.sh` PASS. BAT review tĩnh, Windows runtime
  NOT RUN vì môi trường hiện tại macOS. Ghi nhận LF cho Git diff-check.

URL dùng tiếp: **http://127.0.0.1:3000/calendar**, `/notifications`.
Gateway: **http://127.0.0.1:8080**. Frontend được giữ trong terminal Orca
`MAIN Frontend :3000`; không đóng dev server 13100 của worker.
Tài khoản dev Teacher/Student trong script vẫn dùng mật khẩu test hiện có;
Admin dùng bootstrap riêng từ `.env`, không hard-code lại Admin credential.

## Graphify

Canonical MAIN đã refresh sau merge đầu `08ec23a`: 238 code files, 2541 nodes,
7887 edges. Bản lịch tháng cuối được refresh tiếp; số liệu/thời điểm/HEAD
hiện hành lấy từ `.git/graphify/out/main/snapshot.json`.
Có Schedule/Availability/ExamSession, Calendar frontend, Follow, Notification
relationships theo AST. Refresh cuối ghi actual HEAD trong snapshot.json.
http://127.0.0.1:8765/main/graph.html. Graph là AST + document inventory, không
semantic/E2E proof; không index unmerged Phase2 vào canonical MAIN.

## Regression và E2E

Frontend trên Git archive committed MAIN `08ec23a`, sau đó kiểm độc lập
candidate `35ea5cc` (cùng năm gate PASS), không copy dirty work:

| Lệnh/kiểm tra | Kết quả |
| --- | --- |
| npm ci | PASS |
| npm exec -- tsc -b --pretty false | PASS |
| npm run build | PASS |
| bash tests/validate-shared.sh | 83/83 PASS, 0 fail/skip |
| npm run lint | PASS |
| Committed diff-check | PASS |
| python3 /tmp/examini-scheduling-main-api-e2e.py | 34/34 PASS, real Gateway18180, không fixture |
| MAIN 3000 → Gateway8080: Teacher/Student me/calendar/unread | 6/6 PASS |
| Browser sau merge MAIN3000 | Teacher/Student login, Calendar/Notification PASS (30cb232); bản tháng mới kiểm riêng |
| Flyway datn PostgreSQL thực | Auth 4, QB 2, Exam 4, Session 3, Grading 2, Notification 2 thành công; 0 failed |

Exam/Session totals gồm repeatable bridge. Snapshot, persisted answer, resume,
idempotent submit, Rabbit grading/result vẫn PASS. Browser flow tạo/chỉnh lịch,
PUBLIC Follow, early block, clock-boundary và mobile đã được worker/MAIN kiểm
trên Gateway18180 với đúng candidate trước merge; sau merge thêm browser trên
runtime mặc định. Bản tháng `35ea5cc` được MAIN kiểm thêm Student desktop/mobile,
popup đủ Exam gồm overflow, no Teacher actions, Tab/Shift+Tab/Escape và focus
trả về ngày gốc. Lần CLI waiter kẹt được giữ là checkpoint FAIL; browser
ROOT mới dùng native input + bounded DOM observations xác minh đủ 6 checks
mobile PASS, không sửa code để làm xanh. API E2E/bộ test/focus/browser là các evidence độc lập, không
coi fixture smoke 8/8 trước các fix cuối là full production E2E.

| Flow | Kết quả và bằng chứng |
| --- | --- |
| Teacher schedule | PASS — API thật + browser candidate được duyệt |
| Teacher reschedule | PASS — Student calendar/notice/revision cập nhật đúng |
| Student upcoming | PASS — Calendar/Lobby/Follow trên browser thật |
| Early start denied | PASS — Join/Start 409, UI khóa theo backend |
| Exam available | PASS — auto lifecycle/clock; browser boundary → Lobby/Start |
| Deadline | PASS — cả ba rule, late entry, extend giữ session cũ |
| Timezone | PASS — UTC/IANA/offset, DST gap/overlap, viewer/Exam display |

Evidence local (có thể không tồn tại sau dọn `/tmp`):
`/tmp/examini-scheduling-main-frontend-gate-result.json`,
`/tmp/examini-scheduling-main-api-e2e-result.json`,
`/tmp/examini-scheduling-main-default-runtime-result.json`,
`/tmp/examini-scheduling-main-default-browser-result.json`,
`/tmp/examini-month-calendar-frontend-gate-result.json`,
`/tmp/examini-month-calendar-browser-review.json`,
`/tmp/examini-month-calendar-mobile-close-review.json`,
`/tmp/examini-month-calendar-main-browser-result.json`,
`/tmp/examini-scheduling-gmail-pipeline-e2e-result.json`.
Không đưa token/Gmail credential/private key vào Git.

## Worktree status và bảo toàn

MAIN có merge/runtime/docs; không rewrite history. Các worktree Frontend,
Exam, Notification, Auth contact, Question Bank và Reporting được giữ nguyên.
Frontend candidate được review sạch; MAIN không merge unique/unfinished Phase2 của sibling.
Follow-up tháng đã stash/restore 21 paths gồm report đang soạn, khớp SHA256.

Dirty MAIN được stash `-u` và private backup trước merge. Đủ 20 trạng thái/file
ban đầu khôi phục đúng SHA256 trước sửa startup. Các startup files liên quan
được commit riêng theo correction của user; README/POM/deletions và hai stop
scripts vẫn là user work chưa commit. DummyIcon vốn user xóa nay đã bị xóa bởi
reviewed FE commit, không còn là dirty entry. Stash original Phase1 `18469bd`
và mọi stash cũ vẫn còn, không drop/pop/clear. Không mất dữ liệu.

## Follow-ups

| Owner | Việc còn lại | Ảnh hưởng |
| --- | --- | --- |
| MAIN + Notification + Frontend | Bật push trên browser/thiết bị thật có permission, kiểm provider và OS display | Chưa thể claim physical push release E2E PASS |
| MAIN + Notification | Xác nhận thư Gmail thật trong Inbox | SMTP ACK đã PASS; Inbox placement chưa chứng minh |
| MAIN/shared | Chạy BAT trên Windows | Đã review/same runtime steps; chưa có Windows execution proof |
| Exam + Frontend | Gộp availability vào public discovery DTO nếu cần | N+1 tối đa 20 requests hiện có, không chặn flow |
| AUTH-IDENTITY | Ghi nhận access JWT còn hợp lệ đến expiry sau logout, refresh bị revoke | Không thêm token blacklisting ngoài scope |

## Kết luận

**SCHEDULING FEATURE INTEGRATED WITH FOLLOW-UPS**.
Manual UI approval đã có, frontend/backend/contracts đã tích hợp, MAIN runtime
mặc định phục vụ đúng bản đã duyệt. Không claim production E2E cho push vật lý
hay Inbox khi chưa có evidence. Không bắt đầu feature tiếp theo.
