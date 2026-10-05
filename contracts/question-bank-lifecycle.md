# Sửa lifecycle Question Bank

Quyết định nghiệp vụ của chủ dự án, 2026-10-05. Trạng thái: REQUIRED,
đang triển khai. Tài liệu này thay thế các quy tắc APPROVED-only toàn cục trong
tài liệu cũ. Routes/DTO chính xác sẽ được xác minh trước tích hợp.

## Quy tắc chuẩn

- PERSONAL: DRAFT / ACTIVE / ARCHIVED; owner activate và sử dụng không review.
  Question status độc lập với trạng thái contribution.
- PUBLIC: publish trực tiếp snapshot versioned, không bắt buộc verification
  trước khi available. Moderation tương lai ngoài scope.
- ORGANIZATION: contribution PENDING_REVIEW rồi APPROVED hoặc REJECTED bởi
  authority rõ ràng của bank. Question cá nhân gốc tồn tại độc lập.
- SYSTEM: quản lý bằng systemPrivilege ADMIN / explicit authority;
  TEACHER không đồng nghĩa ADMIN.
- Fork/copy: Question mới độc lập, giữ source/version/bank lineage.
  Published Public snapshot và Exam không thay đổi theo source.

## Exam: contract eligibility cần chốt với provider

Provider kiểm tra bank/source, trạng thái và quyền actor. PERSONAL ACTIVE có
quyền, PUBLIC published có quyền, SYSTEM ACTIVE có quyền và ORGANIZATION
publication APPROVED có quyền có thể sử dụng. DRAFT/ARCHIVED và Organization
contribution chưa approve không eligible.

Consumer không được thêm lại điều kiện status=APPROVED toàn cục hoặc nhận identity
do client tự tạo. Internal access hiện có phải giữ authentication; candidate DTO
không chứa answer key. Publish Exam chụp version/content/options/points bất biến.

## Rollout và phạm vi

Migration additive, giữ IDs/versions/legacy reviews/snapshots. Question Bank
bàn giao routes/DTO/mapping; MAIN review trước khi giao frontend.
Organization membership đầy đủ, moderation/ratings, Notification/Analytics UI và
Browser Integrity frontend ngoài scope. Organization access phải có explicit
authority; không suy quyền toàn cục từ Teacher persona.

MAIN giữ dirty user work, không push. Backend tích hợp trước frontend;
refresh Graphify sau mỗi bước tích hợp đã review.
