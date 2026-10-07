# Scheduling — điều phối và review kỹ thuật MAIN

Trạng thái: Product/Domain Scope Lock đã duyệt, gồm PUBLIC flag kèm Notification
và lựa chọn A cho attempt hiện hành. Auth contact, Exam/Session Scheduling và
Notification đã tích hợp vào MAIN. Backend E2E qua Gateway thật đạt 34/34 checks
ngày 2026-10-07. Frontend đã sync/khảo sát và user duyệt toàn bộ khuyến nghị
UI/UX; Frontend đã hoàn tất và MAIN technical review PASS theo
`exam-calendar-ui-scope.md`. Người dùng đã trả lời “Duyệt UI để MAIN merge”
ngày 2026-10-07. Candidate `30cb232` đã merge vào MAIN tại `08ec23a`;
unified frontend regression và real Gateway E2E PASS. Kết quả cuối:
SCHEDULING FEATURE INTEGRATED WITH FOLLOW-UPS, còn kiểm push vật lý/Gmail Inbox.
Follow-up UI user trực tiếp: `35ea5cc` (lịch tháng/popup) đã review và merge
tại `035c586`, thay bốn tab theo amendment trong UI/UX Scope Lock.
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
- MAIN duyệt CR msg_86077c344db3: thêm `exam.recipients.changed` v1, payload
  examId/recipientRevision/cause/actorUserId; cause ASSIGNED/UNASSIGNED/
  MEMBER_ADDED/MEMBER_REMOVED/FOLLOWED/UNFOLLOWED/JOINED. Snapshot thêm recipientRevision.
  Outbox và revision trong transaction đổi quan hệ; idempotent operation không
  phát change giả. Không chứa danh sách user IDs/PII trong event này.
- Schedule, lifecycle và recipient revision là ba sequence riêng. Consumer lấy
  snapshot authority hiện hành, hủy jobs mất quyền, chỉ lập future reminders;
  không thêm thông báo lịch khi Join/assignment/follow và không gửi bù mốc đã qua.
- MAIN duyệt CR msg_bb382de02ff9: JOINED chỉ phát khi Join tạo quan hệ mới;
  operation idempotent không tăng revision/event giả. Không thêm UNJOINED vì
  chưa có thao tác rời Exam, không mở rộng scope thành leave feature.
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

### Quyết định migration và Web Push ngày 2026-10-06

- MAIN trả lời CR msg_2b35d7a75f49: Draft create/update phát
  `exam.schedule.changed`; Publish chỉ phát `exam.lifecycle.changed` với
  `status=PUBLISHED`. Consumer đọc snapshot hiện hành để tạo reminder và thông
  báo đầu tiên cho Student, dedup theo `(examId, scheduleRevision)`. Đây là
  quyết định thay cho đề xuất producer phát cả hai sự kiện lúc Publish.
  Consumer vẫn tương thích thứ tự cũ phát cả hai, nhưng phải kiểm thử chuỗi
  producer thực tế mới. Draft chỉ thông báo OWNER/MANAGER, không Student.

- MAIN trả lời CR msg_e7532333a903: Scheduling được dùng additive repeatable
  `R__` làm cầu nối MAIN chưa có migration Phase2 và branch đã có Phase2.
  Không sửa checksum/version Phase2, không bật outOfOrder hay reset DB.
  Legacy UTC backfill phải có guard transaction chạy một lần; lần chạy lại
  không được ghi đè lịch/revision đã sửa, kể cả lịch bị bỏ về null hợp lệ.
  Review yêu cầu PostgreSQL/Flyway thực: baseline MAIN → Scheduling;
  Phase2 → Scheduling; MAIN Scheduling → Phase2; checksum repeatable thay đổi
  vẫn giữ dữ liệu. Migration tương lai thông thường tiếp tục dùng versioned.
- MAIN trả lời CR msg_480290ae5fed: dùng `web-push:5.1.2` cho RFC8291/VAPID,
  `bcprov-jdk18on:1.86` và `jose4j:0.9.7`, loại transitive CLI/async Netty/
  BC cũ. Đây là dependency mới có mục đích, không upgrade ngoài scope.
  Xác minh bản phát hành bằng [BouncyCastle](https://www.bouncycastle.org/download/bouncy-castle-java/)
  và Maven Central metadata trực tiếp ngày 2026-10-06. Worker phải kiểm thử
  lại vector, ES256, round trip và cây dependency với tổ hợp này; báo MAIN
  nếu có incompatibility trước khi thay bản. Không tự triển khai protocol crypto.
  Transport dùng JDK HttpClient không redirect, kiểm endpoint cả lúc subscribe
  và gửi; retry từng subscription, không coi provider accepted là browser display.

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
  observed; đã succeeded tại c20a651. Follow-up task_8b0da8f833d3 /
  ctx_6eb0eea0de03 succeeded tại aa32371, sửa wording reminder trễ.
  MAIN review diff và Surefire từ archive aa32371: 52/0/0/0 trên PostgreSQL 16,
  RabbitMQ 3.13, Mailpit. Merge component tại 13b5f28 trước Exam là dependency
  độc lập để nhận event; không merge feature Exam Phase2 chưa review.
  Cả hai Dispatch đã release theo đúng settlement; external terminal retained,
  không close/delete workspace. Gmail delivery, browser push và producer Exam
  E2E chưa được xác nhận; contracts/notification-scheduling.md phân loại rõ.
- Frontend chưa dispatch Scheduling: chờ backend MAIN review, sau đó UI/UX gate.
- Backend worker đã succeeded `92ddd2f`, Dispatch ctx_6e480f5adec9 settled;
  MAIN đã release đúng authority, external terminal retained, không đóng agent.
  MAIN thực tế đã có `59c1017` áp Scheduling độc lập; không merge toàn branch
  Phase2/QB compatibility. Domain/API/migration review được tiếp tục trên chính
  commit này; real Scheduling E2E vẫn pending, chưa claim feature PASS.
  Bốn scratch wt2/base/ph2/full chỉ có port test cục bộ. HEAD đều reachable từ
  main hoặc feature/exam-taking-domain; patch/file/Surefire được lưu riêng tại
  .git/coordination/scheduling-scratch-retirement-20261007 trước khi gỡ theo
  yêu cầu user. claude-pg-test/claude-rmq-test đã stop, auto-remove; không đụng
  stack datn đang chạy. Dirty MAIN và stash gốc kiểm SHA256 vẫn nguyên.
  Runtime/package dùng JDK21; không dùng JDK27 để kiểm Lombok.
- Checkpoint ngày 2026-10-07: MAIN runtime Auth/Gateway/Notification riêng đạt
  43/43 checks, PostgreSQL Auth V1–V4/Notification V1–V2 thành công. Đây chỉ là
  API/component gate, chưa producer Scheduling, Gmail hoặc browser push thực.
  Chi tiết tại notification-scheduling.md. Exam worker tiếp tục các phần độc
  lập. Người dùng đã trả lời A cho `msg_4f5be95a5cb0`: xét attempt hiện hành,
  lượt 2 đang IN_PROGRESS vẫn nhận Close/extend dù lượt 1 đã nộp. MAIN đã reply
  `msg_2f1b2e504497`, ACK delivery của câu hỏi và cập nhật scope/contract.
  Frontend vẫn chờ review backend ổn định, không tự triển khai.
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

## Backend runtime gate ngày 2026-10-07

- MAIN Scheduling `59c1017` được review theo Product/Domain Scope Lock, không
  mang theo các commit Browser Integrity/Question Bank compatibility chưa duyệt.
- Runtime phát hiện Jackson chuyển offset request về UTC trước khi kiểm IANA
  timezone. Worker Exam sửa riêng hai field trong `ScheduleDto.Update` bằng
  `@JsonFormat(without = ADJUST_DATES_TO_CONTEXT_TIME_ZONE)`, không thay Jackson
  toàn cục. Commit provider `c5ac78fb1924ed2815a8d16f9ea1edf3549ad436` được
  cherry-pick vào MAIN tại `58bbd76c4c2c953f39c18decac301b75243ef3ac`.
- Task `task_99897fc5c794` / Dispatch `ctx_f97f826d7518` succeeded, report
  `/tmp/examini-scheduling-timezone-fix-result.md`. MAIN review toàn diff và
  Surefire: branch 24 executed PASS + 1 skipped; MAIN archive 19 executed PASS
  + 1 `OutboxContractTest` skipped vì chưa bật cờ RabbitMQ trong lượt unit test.
  JDK 21 + PostgreSQL 16 thật; không dùng JDK 27. Dispatch đã release đúng
  settlement, terminal bên ngoài retained, không đóng hay tạo worker trùng.
- MAIN E2E: `python3 /tmp/examini-scheduling-api-e2e.py` — **34/34 PASS**.
  Bằng chứng `/tmp/examini-scheduling-api-e2e-result.json`; nguồn Exam là
  `58bbd76`, các service còn lại từ `59c1017`. Stack riêng loopback Gateway
  `http://127.0.0.1:18180`; không thay stack `datn` hoặc đưa dirty MAIN vào build.
  Java 21/Maven 3.9, PostgreSQL 16, Redis 7, RabbitMQ 3.13, Mailpit; bảy service
  Auth/QB/Exam/Session/Grading/Notification/Gateway health UP.
- E2E thật xác nhận +07:00 → UTC, offset sai/DST gap bị từ chối, DST overlap;
  authorization OWNER/MANAGER/PROCTOR/unrelated Teacher; PUBLIC Follow không
  tạo Participant; preview trước giờ, Join/Start bị chặn sớm; reschedule và
  stale revision; manual Open không bypass; đủ ba deadline rules; auto Open/Close;
  resume/answer persistence; snapshot không đổi sau sửa Question; submit lặp
  idempotent, RabbitMQ grading/result dùng snapshot; Close vẫn cho active Session
  hoàn thành; Reopen/Unfollow; lượt 2 IN_PROGRESS nhận Close dù lượt 1 đã nộp.
  Flyway cả sáu database Auth/QB/Exam/Session/Grading/Notification thành công.
- Outbox → RabbitMQ → recipient/Auth API → in-app Scheduled/Rescheduled và
  SMTP Mailpit PASS. Harness đã sửa để đợi notice trước khi nộp bài; assertion
  cũ đợi thông báo cho Student đã submitted không phù hợp authority hiện hành.
  Failure ban đầu được giữ riêng trong `/tmp`, không thay business rule để làm
  test xanh. Mailpit không chứng minh Gmail delivery; browser push/display,
  browser Calendar và manual UI validation vẫn chưa chạy.
- Dirty MAIN restored kiểm SHA256 đúng nguyên trạng sau cherry-pick; manifest
  `.git/coordination/scheduling-timezone-merge-preservation.json`. Backup stash
  riêng và original Phase1 stash vẫn giữ; không discard hay push.

MAIN chấp nhận backend/API để giao Frontend clarification. Contract hiện hành:
`exam-scheduling-api.md`, `exam-scheduling-scope.md`, `notification-scheduling.md`.
Frontend phải dùng committed MAIN, giữ các commit Product UI hiện có, hỏi một
lượt UI/UX có khuyến nghị qua Orca. Chưa có UI/UX Scope Lock thì không implement.

### Frontend clarification đã giao

Task `task_64a855e02cbf` / Dispatch `ctx_186d17bdb03e`, Run
`run_3b1c99897538`, reused terminal `term_6f901d86-f6de-4ef5-ae1c-b7c4262295b8`
tại worktree `frontend/app`. Orca receipt: ready/input_accepted,
turnStart observed; không tạo agent/worktree mới hoặc thay model.
Baseline giao worker: committed MAIN `de83ea1`; frontend HEAD ban đầu
`bdf5239`, sạch, chín commit Product UI chưa merge phải giữ.
Task cho phép safe merge đúng committed baseline trong checkout của worker,
không copy dirty MAIN hay merge các domain Phase2 chưa duyệt.

Frontend khảo sát design system và hai role trước; gửi một nhóm câu hỏi UI/UX
có khuyến nghị bằng Orca ask để MAIN chuyển người dùng. Implementation chỉ sau
câu trả lời rõ ràng và UI/UX Scope Lock. Runtime backend thật qua Gateway 18180
có sẵn; login test riêng chỉ local, không chứa service/SMTP credentials.
Sau tests/browser, worker dừng ở READY FOR MANUAL UI VALIDATION; user duyệt
UI trước MAIN review/merge cuối. UI/UX **APPROVED**, manual validation **PENDING**.

Worker đã sync sạch tại `6714709`, giữ nguyên Product UI, khảo sát API thật và
gửi câu hỏi `msg_a1fb86f267f4` trong delivery `delivery_554044c44b3e`. MAIN đã
chuyển sáu nhóm quyết định UI/UX đến user. User trả lời “duyệt” cho toàn bộ
khuyến nghị ngày 2026-10-07; scope được khóa trong `exam-calendar-ui-scope.md`.
MAIN trả lời đúng câu hỏi rồi ACK delivery; không tự quyết thay user.
Decision context lưu local tại
`.git/coordination/scheduling-frontend-pending-user.json`; worker chờ cùng
message ID, không tạo câu hỏi/Dispatch trùng. Task hiện hữu tiếp tục implementation
sau reply; không tự merge hoặc vượt manual UI gate.

Stack test Notification đã provision VAPID từ cấu hình local, public-key API
qua Gateway trả enabled=true; private key không vào Git/frontend/handoff.
SMTP vẫn Mailpit, không gửi địa chỉ test qua Gmail. Đây là prerequisite cho
browser push gate sau UI/UX, chưa chứng minh subscription/provider/display.

MAIN gửi một smoke test SMTP Gmail về chính sender đã cấu hình: SMTP accepted,
Inbox chưa verify; evidence `/tmp/examini-scheduling-gmail-transport-smoke-result.json`.
Không gửi lặp tự động, không gửi tới địa chỉ fixture và không coi transport test
là producer/Notification/Gmail E2E. UI/UX Scope Lock đã được user duyệt;
browser validation và manual UI gate còn phía trước.

MAIN đã kiểm thêm pipeline producer → Notification → Gmail trên services/
broker/database tách biệt: 5/5 checks PASS, in-app và một logical EMAIL SENT
sau Gmail ACK, chỉ recipient chính sender đã cung cấp. Evidence
`/tmp/examini-scheduling-gmail-pipeline-e2e-result.json`. Temporary containers
đã gỡ, không thay stack Calendar/datn. Inbox và browser link/push/display còn
chưa verify; không gửi lặp tự động hoặc coi SMTP ACK là release UI approval.

## Frontend review và manual UI gate ngày 2026-10-07

- Candidate `frontend/app`: `30cb232dcf3bf500f91ec99daa254429100378f6`, worktree
  sạch. Giữ nguyên các commit Product UI trước task; không sửa backend/contracts
  trong worker và không push/merge MAIN. Report tiếng Việt:
  `frontend/EXAM_CALENDAR_RESULT.md` trong worktree `frontend/app`.
- Task chính `task_64a855e02cbf` / `ctx_186d17bdb03e` succeeded tại `b70a31a`.
  Follow-up Tắt push `task_cc2c21c1fccf` / `ctx_a5b7cab2a549` succeeded: fix
  `7012e17`, report `d2e5d99`. Follow-up EOF `task_0ee58205d1df` /
  `ctx_d3eee61974f2` succeeded tại `30cb232`. Cùng terminal Claude đã được reuse;
  không tạo agent/worktree hoặc đổi model. Release đã được xử lý sau settlement;
  terminal bên ngoài và Vite được giữ nguyên, không còn cần cleanup decision.
- MAIN review PASS: trục/nhãn DST, start-only Agenda, metadata editor giữ UTC,
  Join bị khóa sau preview, focus mobile/dialog lồng nhau, availability làm mới
  tại mốc giờ, và cleanup push khi logout/đổi tài khoản. Enable/reconcile/disable
  dùng hàng đợi và guard phiên; chỉ thao tác đúng subscription/token đã capture.
  Unit test bắt hành vi lỗi cũ; không gọi fake PushManager là push vật lý.
- MAIN kiểm tra độc lập Git archive của `d2e5d99`: `npm ci`, `npm exec -- tsc -b
  --pretty false`, `npm run build`, `bash tests/validate-shared.sh` (83 tests),
  `npm run lint` đều PASS. Commit `30cb232` chỉ bỏ một dòng trắng EOF; code tương
  đương bản đã test. Committed-range `git diff --check` PASS sau fix. Evidence:
  `/tmp/examini-scheduling-frontend-gate-result.json`; failure EOF ban đầu được
  giữ trong manifest, không che kết quả kiểm tra.
- Browser thật qua Gateway: worker kiểm Teacher tạo/chỉnh lịch, revision/DST,
  lifecycle, Proctor chỉ đọc; Student Follow, preview/Join sớm bị khóa,
  availability đổi theo giờ, Lobby/Start và mobile. MAIN kiểm độc lập mobile
  native Tab/Shift+Tab/Escape và focus trả lại, Student đủ bốn view/Upcoming
  Lobby, chuông markRead → Lobby và unread giảm. Không dùng fixture cho các flow
  này. Smoke fixture 8/8 là chứng cứ route guard riêng ở bản trước các fix cuối.
- Giới hạn: physical push subscription/provider/OS display chưa verify vì Chrome
  automation từ chối permission; Gmail SMTP ACK không chứng minh Inbox placement.
  Không giả delivery hay tuyên bố UI/release đã được user duyệt.
- Runtime để user kiểm tra: `http://127.0.0.1:13100/calendar` và
  `/notifications`, Gateway `http://127.0.0.1:18180`. Credentials test chỉ local
  tại `/tmp/examini-scheduling-frontend-test-accounts.json`, không đưa vào Git.
- Theo manual UI gate (#28) do user yêu cầu, MAIN chờ user duyệt bản chạy thật
  trước merge Frontend. Sau duyệt: bảo toàn dirty MAIN/stashes, merge candidate,
  refresh canonical Graphify, chạy unified regression/E2E rồi báo cáo cuối.
  Canonical graph hiện phản ánh backend MAIN đã duyệt; chưa index UI chưa merge.


## Tích hợp cuối và sửa runtime mặc định ngày 2026-10-07

- Manual UI gate #28 đã được user duyệt rõ ràng; không coi lần duyệt scope trước
  là approval bản UI. MAIN merge `frontend/app@30cb232` bằng `--no-ff` tại
  `08ec23a1ecffdbd372438f6cccb905d40de87595`, không có branch conflict. Không
  merge toàn bộ Exam Phase2/QB Phase2, không push và không mở feature tiếp theo.
- Dirty MAIN mới nhất gồm 20 mục đã backup private và `git stash push -u` trước
  merge. Restore có modify/delete conflict ở browser-smoke.py, fixture-server.mjs,
  validate-shared.sh: bản đã review vẫn lưu trong committed HEAD; giữ nguyên
  việc xóa của user ở working tree. Kiểm SHA256 xác nhận đủ 20 trạng thái/file
  gốc được bảo toàn trước bước sửa startup mà user yêu cầu. Original Phase1
  stash `18469bd` và mọi stash trước task vẫn còn.
- Sau merge, Git archive committed MAIN `08ec23a`: `npm ci`, `npm exec -- tsc -b
  --pretty false`, `npm run build`, `bash tests/validate-shared.sh` (83/83,
  không skip), `npm run lint` PASS. Không phục hồi test user đã xóa chỉ để chạy
  gate. Evidence `/tmp/examini-scheduling-main-frontend-gate-result.json`.
- `python3 /tmp/examini-scheduling-main-api-e2e.py`: 34/34 PASS sau merge trên
  Gateway thật 18180, Auth/QB/Exam/Session/Grading/Notification thật, PostgreSQL/
  Redis/RabbitMQ/Mailpit. Backend binaries không đổi so với baseline đã test;
  frontend tree của MAIN bằng candidate được duyệt. Đây là API E2E, tách biệt
  với browser; không gọi fixture smoke hoặc SMTP ACK là browser/physical push.
- Canonical Graphify clean MAIN `08ec23a`: 238 code files, 2541 nodes, 7887 edges;
  Calendar/Notification frontend đã nằm trong graph. Graph chỉ là AST/document
  inventory, không chứng minh business invariant. Refresh cuối ghi HEAD ở
  `.git/graphify/out/main/snapshot.json`; xem http://127.0.0.1:8765/main/graph.html.
- User báo start.bat/start.sh mở giao diện khác ở 3000: xác minh PID phục vụ
  MAIN/frontend cũ, còn frontend/app tại 13100 chưa merge. Sau approval/merge,
  3000 phục vụ đúng UI đã duyệt. Đồng thời Docker 8080 là backend cũ: calendar
  400 (parse UUID), notifications 404; đã rebuild/recreate bằng startup thật.
- Commit shared runtime riêng `ae7b2db`: giữ logic tài khoản dev hiện có, chạy
  từ thư mục frontend của checkout, cố định 3000/strictPort, chờ Compose health,
  bổ sung Docker Desktop PATH cho macOS và tạo notification_db còn thiếu trên
  volume cũ bằng SQL idempotent. Không xóa volume/tables/dữ liệu. Chỉ hai script
  startup liên quan và SQL được commit; README/POM/deletions user không bị commit.
- `bash -n start.sh`, chạy `bash start.sh`, SQL lần hai không tạo DB lại và
  diff-check PASS. Docker datn core + Notification đã healthy; Flyway sáu DB
  thành công (Auth 4, QB 2, Exam 4 gồm repeatable, Session 3 gồm repeatable,
  Grading 2, Notification 2; không migration failed). Windows BAT đã review,
  chưa chạy trên Windows trong môi trường macOS này.
- Browser ROOT sau merge tại 3000 → Gateway8080: Teacher/Student login, đủ
  Agenda/Month/Week/Day, đúng menu từng persona và trang Notification PASS;
  sáu API me/calendar/unread-count PASS. Runtime giữ ở terminal Orca
  `MAIN Frontend :3000`, không dừng Vite 13100 của worker. Evidence
  `/tmp/examini-scheduling-main-default-browser-result.json` và
  `/tmp/examini-scheduling-main-default-runtime-result.json`.
- Push subscription/provider/OS display thật và Gmail Inbox placement vẫn
  chưa verify; implementation đã tích hợp, không claim toàn bộ kênh release
  E2E PASS. Báo cáo: `contracts/exam-scheduling-result.md`.


### Follow-up lịch một tháng — user yêu cầu trực tiếp

- Handoff `msg_c8fa4391b6bd`, delivery `delivery_e3a5680198b2`, user nói
  “gửi lên main để merge đi” sau khi worker implement lưới tháng + popup ngày.
  MAIN xác minh terminal hiện hữu; đã xử lý toàn message và ACK delivery.
  Không làm mất draft trong terminal, không tạo agent/Dispatch mới hoặc ép
  worker_done bằng authority đã settled. Status phản hồi qua mailbox hiện hữu.
- Candidate `35ea5cc4bd1092da24f31f3b6fea7cfa241762ae`, sạch, đúng 5 frontend files; không backend/API
  change. Bỏ tab Agenda/Week/Day trên calendar page là user amendment rõ ràng,
  không tự chọn thay product owner. Dashboard Today/Upcoming/unscheduled,
  permission, clock-boundary, timezone và Notification vẫn giữ.
- MAIN kiểm độc lập archive candidate: npm ci/tsc -b/build/lint và 83/83 tests
  PASS. Test các helper view cũ vẫn còn, không gọi chúng là UI test của lưới mới.
- MAIN browser Student thật trên Gateway18180: 35 ô/no tabs, popup đủ 3 Exam
  gồm overflow, Student không có Teacher actions; desktop/mobile, focus,
  Tab/Shift+Tab, sheet transition, Escape/unlock/day-focus và không tràn ngang.
  Lượt CLI waiter đầu timeout và làm session bị kẹt; giữ artifact FAIL, đóng
  đúng browser ROOT rồi kiểm lại với DOM observations có timeout. Sáu checks
  trong browser mới PASS, không sửa code/fixture để che lỗi.
- Merge --no-ff tại `035c5862980157a01a50e9e9b27d2b760f217c0c`, không conflict. Stash/backup giữ cả dirty user
  và report đang soạn; restore đủ 21 paths đúng SHA256, original stash giữ.
  MAIN frontend tree bằng candidate, các backend trees không đổi. Bản tháng
  được kiểm thêm trên runtime MAIN3000 sau merge, không chỉ Vite13100.
- Evidence: `/tmp/examini-month-calendar-frontend-gate-result.json`,
  `/tmp/examini-month-calendar-browser-review.json` (giữ checkpoint timeout),
  `/tmp/examini-month-calendar-mobile-close-review.json`,
  `/tmp/examini-month-calendar-main-browser-result.json`.
