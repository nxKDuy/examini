# Question Bank — Organization Bank & Public Bank API

CURRENT: MAIN review + merge `2621526` (QB `7ae5390`, `12e22b9` trên lifecycle `3188370` và multi-bank Phase 2), Exam consumer `1b161f3` dùng cờ `eligible` thay `status==APPROVED`. Scope: [parallel-participants-organization-scope.md](parallel-participants-organization-scope.md). Gateway route các prefix `/question-banks`, `/public-questions`, `/public-contributions`, `/organization-questions`, `/organization-contributions` (JWT bắt buộc).

## API mới/đổi (mọi route cần Bearer JWT; actor từ JWT/QB service lane, không từ client)
| Method path | Quyền | Ghi chú |
|---|---|---|
| GET /public-questions?bankId&sourceOwnerUserId&questionType&difficulty&text&page&size | Teacher/Student đã đăng nhập | chỉ PUBLISHED; `text` (≤200 ký tự, LIKE escaped) tìm trong content; size ≤100; không key |
| GET /public-questions/{id} | đã đăng nhập | ARCHIVED chỉ owner thấy, người khác 404 |
| DELETE /public-questions/{id} | owner (sourceOwnerUserId) | 204; unpublish: ẩn discovery, chặn fork/Exam select mới; 404 nếu không owner; 409 nếu đã archive; giữ lineage/fork/Exam snapshot |
| POST /questions/{id}/publish-public {sourceQuestionVersion} | Teacher owner, Personal ACTIVE current version | 201; displayName qua Auth (503 nếu Auth/token lỗi); version đã archive => 409 (publish version mới là thao tác riêng, lineage theo version) |
| POST /public-questions/{id}/fork | Teacher (Student 403) | 409 nếu ARCHIVED |
| GET /question-banks | đăng nhập | ORGANIZATION chỉ org có membership ACTIVE (BANK_READ, kể cả org ARCHIVED) lấy từ Org `/organizations/internal/memberships`; provider lỗi => 503 |
| POST /question-banks/{id}/questions | — | ORGANIZATION/PUBLIC bank => 400 (chỉ qua contribution/publish) |
| GET /organization-contributions?bankId&status&mine=true|false | mine=true: BANK_READ + chỉ submission của mình; mine=false: BANK_REVIEW | history |
| GET /organization-contributions/{id} | người gửi (BANK_READ) hoặc reviewer (BANK_REVIEW); includeAnswers cần BANK_REVIEW | |
| GET /organization-questions(/{id}) | BANK_READ (ARCHIVED org đọc được) | |
| Org submit/review/fork/eligibility | giữ nguyên route; USE/SUBMIT/REVIEW/MANAGE chỉ org ACTIVE; self-review 403 | không cache allow, không bank_authorities |

PublicResponse (đổi): `id, bankId, sourceQuestionId, sourceQuestionVersion, sourceBankId, sourceOwnerUserId, sourceOwnerDisplayName, publicationStatus (PUBLISHED|ARCHIVED enum), publishedBy, publishedAt, archivedBy, archivedAt, question(SafeQuestionResponse: không đáp án/expectedAnswer/excerpt)`. Không có email. Row legacy được backfill displayName "Unknown user".
Exam lane: `POST /questions/batch?eligible=true` và `GET /questions/random` bỏ qua PublicQuestion ARCHIVED (409 ở batch); Exam snapshot đã publish không đổi.
Lưu ý: `legacy bank_authorities` không còn được dùng trong code QB.

## Wiring (MAIN)
QB env: `AUTH_SERVICE_URL`, `QUESTION_BANK_IDENTITY_TOKEN` (fail closed → publish 503), `ORGANIZATION_SERVICE_URL`, `ORG_AUTHZ_QB_TOKEN` (provider lỗi → 503).
