# Scheduling — điều phối và review kỹ thuật MAIN

Trạng thái: scope/domain đã duyệt tại `ee2096c`; PUBLIC flag còn chờ quyết định
notification. Các API/event dưới đây là **đề xuất**, chưa được triển khai.
Nguồn sản phẩm: `exam-scheduling-scope.md`; không thay business rule bằng file này.

## Task khảo sát đã hoàn tất

| Owner | Task / Dispatch | Outcome và bằng chứng |
| --- | --- | --- |
| Exam | task_871bb2752b8f / ctx_88e92c46c638 | succeeded cho KHẢO SÁT; report `/tmp/examini-exam-scheduling-domain-survey.md`, HEAD 6bdf453, sạch, không code/migration |
| Notification | task_ed003ca4e9de / ctx_caac4eff4a87 | succeeded cho KHẢO SÁT; report notification-scheduling-survey.md, HEAD 2163889, sạch, không code/migration |

Hai terminal được giữ lại theo yêu cầu dùng workspace lâu dài. Chưa có Dispatch
implementation Scheduling, chưa giao UI. Không coi khảo sát là feature PASS.

## Auth contact — scope tối thiểu cho worker riêng

MAIN đã xác minh User hiện chỉ có id/email/fullName và không có account-active hay
emailVerified. Không invent `active` từ persona/role hoặc thêm activation domain.

Provider proposal, cần review implementation trước khi chốt public contract:

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
- Worker mới CHƯA KHỞI CHẠY: chờ người dùng chọn model. Base committed MAIN;
  không mang dirty files. Nếu model không được Orca hỗ trợ, báo đúng lỗi, không
  tự thay bằng model khác.

## Exam và Notification — cần chốt schema chung

- Exam authority: schedule/availability/participants/calendar flags; Notification
  authority: reminder jobs, inbox, channel deliveries/subscriptions.
- Đề xuất additive events `exam.schedule.changed`, `exam.lifecycle.changed`, v1
  envelope của examini.events, stable eventId/outbox/confirm và revision.
- Recipient API hẹp dùng EXAM_NOTIFICATION_TOKEN riêng, không broad token mở
  answer keys. Không chứa private audit reason/join code/key/score.
- Recipient assignment/membership/flag thay đổi sau đặt lịch phải được phản ánh;
  không dùng danh sách snapshot ban đầu như authority vĩnh viễn.
- Final DTO/route/schema và relation cho PUBLIC flag đợi gate người dùng.

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

MAIN còn dirty user work, gồm README/POM/start scripts và nhiều file bị xóa từ
trước task. Không khôi phục/xóa/commit những file đó trong setup scope. Chỉ commit
tài liệu điều phối riêng. Trước merge implementation sẽ bảo toàn toàn bộ dirty
state kể cả untracked; original Phase1 stash không được drop/pop/apply.

Backend phải MAIN review trước Frontend clarification/implementation. Frontend
đến READY FOR MANUAL UI VALIDATION; người dùng duyệt trước merge cuối. Merge
backend rồi frontend, Graphify cho code đã duyệt, regression và Gateway E2E.
Không push remote và không tự bắt đầu feature kế tiếp.
