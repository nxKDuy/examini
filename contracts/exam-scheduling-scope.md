# EXAM SCHEDULING & CALENDAR — Scope locks

## PRODUCT SCOPE LOCK — ĐÃ CHỐT

Nguồn: các câu trả lời trực tiếp của người dùng ngày 2026-10-06.
Đây là yêu cầu sản phẩm đã duyệt, **không phải mô tả API đã triển khai**.
MAIN tại lúc chốt: `5272bf3d97138abbe5473ffa053da4cf4f985fd0`.

### Lịch, availability và quyền

- Lịch không bắt buộc. Exam có thể Publish không có lịch và Open/Close thủ công.
- Với Exam có lịch, Teacher cấu hình `autoOpen` và `autoClose` độc lập.
- Student được xem Exam mà mình có quyền truy cập trước giờ; **Join và Start
  đều phải chờ startAt**. Điều kiện lifecycle, access, attempt và capacity vẫn
  bắt buộc. Manual Open sớm không bỏ chặn startAt.
- OWNER/MANAGER được Open/Close sớm. PROCTOR và Teacher không có membership
  quản lý không được thực hiện. TEACHER != ADMIN.
- Manual Close không bị scheduler tự mở lại. Reopen thủ công chỉ được trước
  endAt, hoặc khi Exam không có lịch. Không thay deadline session cũ.
- Early Close ngăn Start mới; session đang làm giữ deadline đã được cấp.

### Ba rule deadline, chọn riêng cho mỗi Exam

| Rule sản phẩm | Start muộn | Deadline session mới |
| --- | --- | --- |
| Đủ thời lượng | Cho phép trong cửa sổ Start | startedAt + duration |
| Nộp đúng giờ kết thúc | Cho phép trong cửa sổ Start | min(startedAt + duration, endAt) |
| Phải còn đủ thời lượng | Chặn nếu thời gian còn lại < duration | startedAt + duration |

Duration tính riêng từ lúc Student Start. Sau khi có Student Start, khóa rule
deadline. Resume không tạo session/deadline mới. Không thêm latestStartAt tùy
chỉnh trong scope này. Backend phải trả canStart/lý do và deadline có authority;
frontend không tự quyết eligibility từ timestamps.

### Đổi lịch

- DRAFT/PUBLISHED: được dời lịch nếu chưa có Student Start, kể cả đã Join.
- OPEN: chỉ kéo dài endAt; deadline các session đã tạo giữ nguyên.
- CLOSED: không chỉnh lịch. Reopen là thao tác lifecycle riêng, không phải quyền
  sửa lịch của CLOSED.
- Chính xác validation tại boundary, rule cho Exam không có endAt và cách ghi
  audit sẽ chốt ở DOMAIN SCOPE LOCK, không được worker tự thay product rule.

### Timezone

- Timezone theo Exam, không hardcode GMT+7. Server lưu thời điểm canonical UTC.
- Teacher cấu hình timezone Exam; UI Teacher/Student hiển thị giờ theo timezone
  Exam có nhãn rõ ràng. So sánh giờ địa phương nếu cần thuộc UI clarification.
- Contract phải xác định timestamp có offset/instant, migration dữ liệu cũ và
  xử lý giờ DST; không đổi nghĩa timestamp cũ một cách âm thầm.

### Notification — đầy đủ ba kênh

- In-app, email và browser push cho Scheduling; không chỉ tạo event placeholder.
- Cover đặt lịch, đổi lịch, Open/Close và reminder trước startAt.
- Teacher cấu hình mốc nhắc, mặc định 24 giờ và 15 phút, có thể tắt.
- Người nhận: Student đã được giao/đã Join/chủ động theo dõi lịch PUBLIC và
  OWNER/MANAGER; không broadcast tới mọi Student khi Exam PUBLIC.
- Đổi lịch hủy reminder cũ và lập lại theo lịch mới; tránh gửi trùng khi retry.
- Chưa có SMTP/Web Push trong Notification hiện tại. Nhà cung cấp, thông tin
  liên hệ tin cậy, subscription/permission, cấu hình và retry là contract/domain
  clarification cần phối hợp Notification và MAIN/Auth; không giả lập gửi thành
  công. Không commit secret, không truy vấn DB Auth từ Notification/Exam.

### Calendar cho cả Teacher và Student

- Cả hai role có Agenda **và** Calendar đầy đủ Month/Week/Day.
- Teacher xem/tạo/chỉnh lịch, Today/Upcoming, trạng thái và thông tin người dự thi.
- Student xem lịch của mình, Today/Upcoming/Available, cửa sổ/duration/countdown,
  completed/missed và click vào Exam detail/lobby.
- Calendar chỉ hiển thị Exam người dùng có quyền xem; cần query theo khoảng thời
  gian và student availability từ backend, không fetch toàn bộ rồi đoán quyền.
- Student có thể chủ động gắn cờ để đưa PUBLIC Exam chưa Join vào My Calendar;
  không tự thêm mọi PUBLIC Exam. Cờ lưu theo tài khoản, bật/tắt được, kèm
  notification/reminder theo chính sách đã chốt; không tự Join/chiếm capacity
  hoặc cấp quyền Start. Đây là quyết định trực tiếp của người dùng.
- Mặc định view, vị trí, card, interaction, màu/badge, mobile và push opt-in phải
  được người dùng chốt trong UI/UX SCOPE LOCK sau backend review.

### Ngoài phạm vi

Không recurring schedule, nhiều ca lặp, accommodation cá nhân (extra time/cửa sổ
riêng), Google Calendar/invitation integration, AI scheduling, Organization đầy
đủ, webcam/anti-cheating mới, source ingestion, analytics/notification ngoài các
nhu cầu Scheduling trên. Không tự merge các feature Phase 2 chưa review.

## DOMAIN SCOPE LOCK — ĐÃ CHỐT CÁC NHÓM DƯỚI ĐÂY

Nguồn: người dùng trả lời các nhóm clarification của Exam và Notification ngày
2026-10-06, gồm PUBLIC Calendar flag kèm notification/reminder.
Đây vẫn là yêu cầu đã duyệt, chưa phải implementation đã được review.

### Exam — lịch và biên thời gian

- Cho phép không lịch, chỉ startAt, chỉ endAt hoặc cả hai. CAPPED_AT_END và
  REQUIRE_FULL_WINDOW cần endAt, không fallback; autoOpen cần startAt, autoClose
  cần endAt. Mặc định FULL_DURATION và auto flags tắt.
- Cửa sổ [startAt, endAt); endAt chặn Start mới kể cả autoClose tắt. Còn đúng
  duration thì Start được với REQUIRE_FULL_WINDOW.
- Cho startAt đã qua nếu endAt chưa qua hoặc không có endAt; không Publish/đổi
  sang lịch đã hết endAt. Giờ DST không tồn tại bị từ chối, giờ trùng cần offset.
- Đổi timezone không tự đổi Instant; timestamp LocalDateTime cũ hiểu là UTC.

### Exam — vận hành và audit

- Start được tính khi session IN_PROGRESS đã lưu bền vững, kể cả mất HTTP
  response. Preflight/Start thất bại không khóa rule. Start và đổi lịch phải
  được tuần tự hóa; deadline đã cấp không bị đổi.
- OPEN chưa có Student Start được đổi rule. startAt/timezone/auto flags chỉ sửa
  khi DRAFT/PUBLISHED; OPEN chỉ extend endAt, không thêm endAt mới vào Exam đang
  không có endAt. Attempt mới dùng revision mới; session cũ giữ deadline.
- Publish giữa cửa sổ với autoOpen bật thì Open. autoClose đóng OPEN hoặc
  PUBLISHED tại endAt. Downtime qua cả cửa sổ không phát Open giả; chỉ Close nếu
  bật autoClose. Nếu autoClose tắt giữ lifecycle nhưng availability đã hết giờ.
- Không Open lần đầu sau endAt; manual Close không bị autoOpen lại.
- Reason bắt buộc khi dời lịch sau Publish, manual Close sớm và Reopen; DRAFT
  edit/Open đúng lịch không bắt buộc. Audit ghi actor, thời điểm, old/new,
  revision; auto actions ghi nguồn SYSTEM.

### Exam — visibility và Calendar

- Student không thấy DRAFT. assigned/joined thấy PUBLISHED/OPEN/CLOSED theo quyền.
- PUBLIC PUBLISHED được khám phá trước giờ; vào My Calendar nếu Student chủ động
  gắn cờ, hoặc đã assigned/joined. Cờ không là ExamParticipant nhưng người theo
  dõi nhận các thông báo đặt/đổi/gỡ lịch, Open/Close và reminder theo ma trận kênh.
  Bỏ cờ loại recipient theo dõi; nếu vẫn assigned/joined thì quyền đó giữ nguyên.
- Code hợp lệ được preview trước giờ, không Join/chiếm capacity/nhận reminder.
- Exam không lịch chỉ hiện Agenda; không bịa ngày cho Month/Week/Day.
- MISSED khi đã qua endAt và chưa có attempt; Close sớm/unscheduled Close chỉ
  báo CLOSED/unavailable.

### Notification — người nhận và biên reminder

- Không thông báo manual action cho chính actor; OWNER/MANAGER khác vẫn nhận.
  Auto action gửi cho OWNER/MANAGER, reminder cũng gửi cho các role này.
- Student không nhận lịch/reminder ở DRAFT; khi Publish Exam đã có lịch mới gửi
  thông báo lịch. Open gửi cho Student liên quan; Close và extend endAt chỉ gửi
  Student chưa nộp (cùng người quản lý theo rule trên).
- Người dùng chốt A ngày 2026-10-07: xét attempt hiện hành, không xét việc đã
  từng nộp. `submitted=true` chỉ khi không còn attempt IN_PROGRESS và attempt
  mới nhất đã SUBMITTED/AUTO_SUBMITTED. Đã nộp lượt 1 nhưng đang làm lượt 2 vẫn
  nhận Close/extend notice. `started=true` nếu từng có durable attempt; expiry
  phải do server xác định để không dùng trạng thái IN_PROGRESS đã quá hạn.
- Không gửi bù mốc nhắc đã qua; job trễ chỉ gửi khi chưa đến startAt và đúng
  revision. Mốc Teacher cấu hình, mặc định 24h/15m; không giới hạn thành chỉ hai
  preset. Join không gửi thêm thông báo lịch; bỏ giao hủy reminder, không thông
  báo bỏ giao. Join trước startAt vẫn bị chặn.
- Gỡ lịch gửi thông báo và hủy reminder. Mỗi lần lưu thay đổi lịch gửi một
  thông báo; không gộp. Close hủy reminder; Reopen chỉ lập các mốc còn tương lai.

### Notification — kênh và nội dung

- In-app và push cover mọi sự kiện Scheduling. Email chỉ cho đặt/đổi/gỡ lịch và
  reminder; không email Open/Close.
- Push chỉ khi có permission/subscription; thiếu thì SKIPPED, không email bù.
- Email gửi tới email đăng ký lấy từ Auth API tin cậy; hiện chưa có xác minh
  email, ghi nhận follow-up Auth. Chưa thêm preference tắt email trong scope.
- Nội dung tiếng Việt: tên Exam, thời gian có timezone, rule deadline, link SPA;
  không join code/câu hỏi/đáp án/điểm/thông tin Student khác.
- Gmail sender đã được người dùng cung cấp qua local .env ignored mode 600.
  SMTP TLS/auth đã kiểm PASS; chưa gửi email, không coi đó là delivery E2E.
  Không đưa địa chỉ sender hay mật khẩu vào Git/handoff.

### Quyền triển khai và UI gate

PUBLIC flag đã được user chốt "kèm nof". Product/Domain gates được khóa; không
còn business assumption cho gate này. Tên/nút/tương tác sẽ chốt ở UI/UX gate.
Workers khảo sát đã settlement; implementation được giao bằng Dispatch riêng.
Worker mới nếu cần: người dùng chọn model trước khi khởi chạy. Reuse worker hiện
có không tự đổi model. Worker Auth contact mới được user chọn Claude Opus 5.5;
MAIN phải xác nhận model thực tế, không tự fallback. MAIN không tự trả lời
business/UX thay người dùng; worker phát hiện quyết định còn thiếu phải hỏi.

## UI/UX SCOPE LOCK — CHƯA CHỐT

Chưa giao frontend implementation. Chỉ sau backend contract được MAIN review,
frontend/app inspect UI rồi hỏi người dùng, chốt lock và triển khai. Kết thúc tại
READY FOR MANUAL UI VALIDATION, cung cấp runtime URL/checklist; không tự merge.

## Điều phối và release gate

Reuse `feature/exam-taking-domain`, `feature/notification`, `frontend/app`.
Exam worker chỉ sửa Exam/Session/Grading; Notification owner sửa dịch vụ của mình;
Frontend owner sửa frontend. MAIN sở hữu contracts, shared configuration và
integration, không thay worker viết business logic.

Merge backend đã review trước frontend đã manual-approved; giữ mọi dirty user
work. Không push remote. Refresh Graphify chỉ cho implementation đã MAIN duyệt.
Release cần migrations thật, tests backend/frontend, gateway-backed E2E Teacher
schedule/reschedule, Student upcoming/early deny/available/deadline và timezone,
cùng bằng chứng delivery ba kênh hoặc blocker cấu hình được ghi rõ.
