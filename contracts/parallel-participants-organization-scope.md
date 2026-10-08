# PRODUCT SCOPE LOCK — Participant/Attempt + Organization/Public Banks

Người dùng duyệt toàn bộ đề xuất MAIN ngày 2026-10-08. APPROVED scope;
không phải báo cáo implementation. Baseline `8a1d000de50a799adcb6bd34934d98556c61090e`.
FAST WORKER MODE và ownership hiện hữu tiếp tục áp dụng.

## A — Participant & Attempt

- OWNER/MANAGER quản lý; PROCTOR chỉ xem roster/trạng thái, không grant/revoke/invalidate.
- Thêm một hoặc nhiều Student Auth hiện hữu qua ID/email; chưa import CSV.
  Cho thêm khi DRAFT/PUBLISHED/OPEN, còn capacity; CLOSED không nhận mới.
- ASSIGNED/JOIN_CODE/PUBLIC: không duplicate participant. Join tự tạo participant
  khi đúng quyền/lịch; Follow tách biệt, không chiếm capacity hoặc tiêu hao attempt.
- Revocation là trạng thái, không xóa lịch sử; chặn Join/Start mới. Active session
  được tiếp tục/resume/nộp theo deadline đã cấp, trừ explicit invalidation.
  Revoked không chiếm slot eligible participant mới; historical count giữ riêng.
- Giữ mặc định Exam attemptLimit=1, Teacher cấu hình Exam như hiện hữu.
  Chỉ Start thành công tiêu hao; resume không tạo lượt mới. Attempt number ổn định,
  không tái sử dụng, mỗi lượt mới là ExamSession độc lập, concurrency-safe.
- Grant từng Student số nguyên dương, default +1, bắt buộc lý do công khai,
  không expiry riêng, không đổi global limit hoặc bypass schedule/capacity.
- Cho invalidate IN_PROGRESS/submitted/graded/released: lý do công khai bắt buộc,
  actor/time audit. Active attempt dừng; session/submission/result/publication/audit
  được giữ, đánh dấu invalid, loại khỏi aggregates kết quả hợp lệ. Không tự hoàn
  lượt; muốn thi lại grant riêng. Grading không được biến kết quả invalid thành valid.
- Student chỉ own participation/history/results; thấy used/remaining, active,
  invalidated và lý do công khai. Private note không lộ.
- Notification hiện hữu: assignment, revocation, grant, invalidation.
  Analytics: registered/eligible counts riêng, không đọc DB Exam/Session trực tiếp.
  Provider events/API cần contract handoff; không overwrite source của Grading.

## B — Organization & Membership

- Teacher tự tạo Organization và là OWNER. Một Auth user có nhiều memberships;
  Student có thể được mời. Không có User/auth thứ hai.
- Organization ACTIVE/ARCHIVED; archive chặn thao tác mới, giữ history/snapshots.
  OWNER archive/update ownership; ADMIN quản lý member/invite/bank trong scope.
- OWNER/ADMIN/MEMBER là resource-scoped, khác systemPrivilege và Exam roles.
  OWNER quản lý Admin/chuyển ownership; ADMIN không tự nâng OWNER hoặc sửa OWNER.
  Luôn có ít nhất một OWNER. Owner cuối phải chuyển quyền trước leave/remove.
- MEMBER có BANK_READ/BANK_USE/BANK_SUBMIT. OWNER/ADMIN thêm BANK_REVIEW/BANK_MANAGE.
  Có thể cấp canReview riêng cho MEMBER; không thêm role Reviewer. Cấm self-review.
  Membership không tự cấp quyền tác giả Personal/Public ngoài contract Question Bank.
- Invitation cho user hiện hữu bằng email/ID; PENDING → ACCEPTED/DECLINED,
  hoặc REVOKED/EXPIRED; hết hạn 7 ngày, chống trùng. Không auto-join hoặc mời user
  chưa đăng ký. Accept chỉ bởi đúng recipientUserId từ verified access JWT.
  Không giả định emailVerified/active vì Auth chưa có các field này.
- Membership ACTIVE/REVOKED/LEFT, giữ history; role/grant changes có audit.
  Revoke/leave hiệu lực ngay lần permission check tiếp theo, không cache allow.
  Không xóa contribution đã duyệt, fork hoặc published Exam snapshots.
- Non-member/inactive membership và provider lỗi đều fail closed.
  System Admin không tự biến thành Organization Owner/Admin.
- Notification invitation/membership dùng hạ tầng hiện hữu, không tạo nền tảng mới.

## C — Organization/Public Bank

- PERSONAL DRAFT/ACTIVE/ARCHIVED; owner dùng ACTIVE không review.
- ORGANIZATION: submit source version snapshot, PENDING_REVIEW → APPROVED/REJECTED.
  Provider Organization quyết định quyền. QB cấm self-review và kiểm ownership/version.
  Original Personal độc lập, rejected contribution không làm original unusable.
- Org bank gắn organizationId. `bank_authorities` legacy không fallback hoặc OR
  vào membership mới; giữ history/data, linkage/migration phải explicit và fail closed.
- PUBLIC: Teacher publish own current ACTIVE trực tiếp, không mandatory review.
  Teacher/Student đăng nhập browse/search, chưa guest. Teacher fork, Student browse.
- Public detail candidate-safe, không trả grading key cho Student; Teacher lấy key
  qua own Personal fork có quyền. Không fetch live source để bù frozen snapshot.
- Owner unpublish/archive: ngừng discovery/selection mới; giữ history/lineage,
  fork/Organization publication/Exam snapshots đã tồn tại.
- Source sửa không đổi Public snapshot cũ. Publish version mới riêng, lineage từng version.
  Public author display name/source version, không public email/private data.
- SYSTEM vẫn explicit system authority; TEACHER != ADMIN.

## D — Frontend gate

Một owner `frontend/app`; trình tự theo contract readiness:
Participant/Attempt → Organization management → Organization Bank → Public Bank.
UI/UX clarification riêng sau backend contract review, chưa khóa layout.
Không đoán API. Hoàn tất ở READY FOR MANUAL UI VALIDATION; MAIN không coi UI
release-ready trước khi người dùng duyệt. MAIN review/merge, Graphify, consolidated E2E.

## Ngoài scope

Question Set/Collection, AI, ingestion, rating/community, webcam, Organization auth
riêng, CSV import roster, anonymous Public, invitation user chưa đăng ký, grant expiry.
