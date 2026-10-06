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
- Người nhận: Student đã được giao/đã Join và OWNER/MANAGER; không broadcast tới
  mọi Student khi Exam PUBLIC. Student chưa Join vẫn nhận nếu đã được giao.
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
- Mặc định view, vị trí, card, interaction, màu/badge, mobile và push opt-in phải
  được người dùng chốt trong UI/UX SCOPE LOCK sau backend review.

### Ngoài phạm vi

Không recurring schedule, nhiều ca lặp, accommodation cá nhân (extra time/cửa sổ
riêng), Google Calendar/invitation integration, AI scheduling, Organization đầy
đủ, webcam/anti-cheating mới, source ingestion, analytics/notification ngoài các
nhu cầu Scheduling trên. Không tự merge các feature Phase 2 chưa review.

## DOMAIN SCOPE LOCK — CHƯA CHỐT

Exam worker phải inspect và gom các quyết định domain còn thiếu, gửi câu hỏi
tiếng Việt qua Orca cho MAIN chuyển đến người dùng. MAIN không tự trả lời thay
người dùng. Chỉ sau câu trả lời mới chốt lock và được giao implementation.
Notification implementation cũng phải qua clarification cho detail còn thiếu.
Các lựa chọn kỹ thuật không đổi behavior do owner quyết định và giải thích.

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
