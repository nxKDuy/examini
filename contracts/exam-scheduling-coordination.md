# Scheduling — điều phối và review kỹ thuật MAIN

Trạng thái: scope/domain đã duyệt; người dùng chốt PUBLIC flag kèm Notification
ngày 2026-10-06. Auth contact đã triển khai và tích hợp; Exam/Notification
Scheduling vẫn đang implementation, chưa được MAIN duyệt như contract hiện hành.
Nguồn sản phẩm: `exam-scheduling-scope.md`; không thay business rule bằng file này.

## Task khảo sát đã hoàn tất

| Owner | Task / Dispatch | Outcome và bằng chứng |
| --- | --- | --- |
| Exam | task_871bb2752b8f / ctx_88e92c46c638 | succeeded cho KHẢO SÁT; report `/tmp/examini-exam-scheduling-domain-survey.md`, HEAD 6bdf453, sạch, không code/migration |
| Notification | task_ed003ca4e9de / ctx_caac4eff4a87 | succeeded cho KHẢO SÁT; report notification-scheduling-survey.md, HEAD 2163889, sạch, không code/migration |

Hai terminal được giữ lại theo yêu cầu dùng workspace lâu dài. MAIN sẽ giao
Dispatch implementation riêng; chưa giao UI. Không coi khảo sát là feature PASS.

## Auth contact — scope tối thiểu cho worker riêng

MAIN đã xác minh User hiện chỉ có id/email/fullName và không có account-active hay
emailVerified. Không invent `active` từ persona/role hoặc thêm activation domain.

MAIN duyệt capability tối thiểu sau khi inspect Auth; implementation phải review:

- `POST /auth/internal/contacts`, body `{userIds: [UUID]}`, tối đa 100 ID.
- Response chỉ `{userId, email, displayName}` cho ID tồn tại; không trả password,
  persona, systemPrivilege, permissions, token; không lưu contact replica lâu dài.
- Credential riêng `NOTIFICATION_CONTACT_TOKEN`, fail closed khi thiếu/sai; không
  access token Student/Teacher/Admin nào thay thế được service credential này.
- Không query DB Auth từ Notification/Exam. MAIN kiểm Gateway public exposure;
  API internal không trở thành public identity lookup.
- Test missing/wrong/empty credential, ordinary Bearer token, UUID/limit, ID không
  tồn tại, response data minimization, regression Auth hiện có.
- Worker chỉ sở hữu auth-service; không thay đăng ký, JWT/role/persona, bootstrap
  hoặc frontend. Shared Gateway/config/contracts phải gửi MAIN riêng.
- Người dùng chọn Claude Opus 5.5 cho worker Auth contact mới. Base committed
  MAIN, không mang dirty files. Nếu model không được Orca hỗ trợ, báo đúng lỗi,
  không tự thay bằng model khác. Handoff ghi model thực tế từ Orca/agent status.

## Exam và Notification — capability MAIN duyệt để triển khai

- Exam authority: schedule/availability/participants/calendar flags; Notification
  authority: reminder jobs, inbox, channel deliveries/subscriptions.
- Additive events `exam.schedule.changed`, `exam.lifecycle.changed`, v1
  envelope của examini.events, stable eventId/outbox/confirm và revision.
- Exam schedule DTO/event: examId/title, startAt/endAt UTC instant nullable,
  IANA timezone, deadlineRule, reminderOffsetsMinutes, scheduleRevision,
  changeType SCHEDULED/RESCHEDULED/UNSCHEDULED, lifecycle và actorUserId.
  Lifecycle event có status/trigger AUTO hoặc MANUAL/lifecycleRevision.
- Recipient API hẹp dùng EXAM_NOTIFICATION_TOKEN riêng, không broad token mở
  answer keys: GET /exams/{id}/internal/notification-recipients. Snapshot có
  revision/lifecycle/schedule/deadlineRule cùng userId/relation/started/submitted;
  không chứa private audit reason/join code/key/score/email.
- Recipient relation gồm OWNER/MANAGER/ASSIGNED/JOINED/FOLLOWER (PUBLIC flag).
  Mỗi user chỉ nhận một thông báo logic dù có nhiều relation.
- Recipient assignment/membership/flag thay đổi sau đặt lịch phải được phản ánh;
  không dùng danh sách snapshot ban đầu như authority vĩnh viễn.
- Provider/consumer chốt tên field/envelope thống nhất trong handoff; không tự
  đổi capability/authority đã duyệt. Route/DTO breaking change gửi MAIN trước.
- Notification được thêm Spring Mail theo BOM hiện tại và Web Push implementation
  chuẩn VAPID có kiểm tra dependency/license/maintenance; không upgrade unrelated
  dependency. Service-local POM commit riêng, MAIN review dependency trước merge.
- SMTP production/development sender dùng cấu hình Gmail local đã cung cấp;
  SMTP test bằng Mailpit/GreenMail không là bằng chứng Gmail delivery. Shared
  Compose/env/Gateway do MAIN làm sau provider contracts, chỉ placeholder Git.

## Những điểm kỹ thuật MAIN sẽ kiểm khi nhận implementation

1. Start/reschedule xuyên hai service: chỉ một revision hợp lệ được dùng; failed
   Start không khóa rule vĩnh viễn, durable session/lost response phải khóa đúng.
   Không coi lock riêng của mỗi DB là transaction chung giữa hai DB.
2. Scheduling patch phải áp dụng độc lập lên committed MAIN. Không kéo Phase2
   8377475/37bced2 hoặc QB compatibility 6bdf453 chưa có provider runtime vào MAIN.
   Owner verify bằng archive/scratch trước handoff, không chỉnh checkout MAIN.
3. Migration số version phải tương thích MAIN và worktree đang có; không đụng
   checksum migration đã chạy, không cần xóa DB để chạy được.
4. Reminder unique key không được ngăn lập lại job đã CANCELLED khi Reopen hoặc
   đổi lịch quay về cùng startAt/offset; job SENT không gửi lại. Revision/recipient
   thay đổi phải được test trên PostgreSQL và broker thật.
5. Nhiều push subscription cùng tài khoản: tracking/retry từng subscription phải
   tránh mất delivery hoặc resend mọi endpoint sau thành công một phần. Không
   tự coi một endpoint thành công là toàn bộ đã thành công.
6. Inbox/broker dedup không chứng minh email/push physical exactly-once khi mất
   provider ACK. Báo rõ mức bảo đảm và trạng thái lỗi/không xác định; không fake
   SENT hay claim E2E từ mock HTTP/SMTP.
7. User credential chỉ local env, không log/mail/handoff/Git. Gmail SMTP TLS/auth
   PASS chỉ là prerequisite, chưa có bằng chứng delivery. Browser push cần UI
   permission/subscription và HTTPS/localhost thực, chưa được test.

## MAIN worktree và integration gates

### Checkpoint implementation ngày 2026-10-06

- Auth contact task_3177b12dab90 / ctx_6e2fcdd09639 succeeded; model thực tế
  Claude Opus 5.5 theo report/terminal. Hai commit 779d9f4 + 4b0f902 đã được MAIN
  review và merge tại 8fc83ad. Terminal mới được release sau settlement; worktree
  và branch giữ nguyên. Contract hiện hành: notification-contact-api.md.
- Gateway 035a0e4: internal-route isolation và strip service headers; 10 tests
  đạt trên Docker JDK21. Shared config a643eb9: token riêng, placeholders SMTP.
- Exam implementation task_9e746ba9558d / ctx_6e480f5adec9: start observed trên
  terminal/worktree cũ. Attempt ctx_28bb99653a05 start_unknown được fenced sau
  quan sát final agent turn chỉ xử lý inbox, không nhận implementation/worker_done;
  retry cùng task không tạo worker/worktree hay sửa model.
- Notification implementation task_49897a0178ea / ctx_4650178a9c97: start
  observed; schema consumer đã route tới Exam. Chưa coi API/event là implemented.
- Frontend chưa dispatch Scheduling: chờ backend MAIN review, sau đó UI/UX gate.
- Toàn bộ dirty MAIN gồm untracked đã stash trước Auth merge, restore kiểm
  SHA256 patch/file và status đúng nguyên trạng. Backup stash giữ nguyên;
  original Phase1 stash vẫn hiện hữu. Không discard/push.

MAIN còn dirty user work, gồm README/POM/start scripts và nhiều file bị xóa từ
trước task. Không khôi phục/xóa/commit những file đó trong setup scope. Chỉ commit
tài liệu điều phối riêng. Trước merge implementation sẽ bảo toàn toàn bộ dirty
state kể cả untracked; original Phase1 stash không được drop/pop/apply.

Backend phải MAIN review trước Frontend clarification/implementation. Frontend
đến READY FOR MANUAL UI VALIDATION; người dùng duyệt trước merge cuối. Merge
backend rồi frontend, Graphify cho code đã duyệt, regression và Gateway E2E.
Không push remote và không tự bắt đầu feature kế tiếp.
