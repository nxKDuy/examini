# Question Bank — Organization Bank & Public Bank API

CURRENT: MAIN review + merge `b341cd5` (additive contracts `7881a88`, lineage privacy `59f2ec5`) trên merge `2621526` (QB `7ae5390`, `12e22b9` trên lifecycle `3188370` và multi-bank Phase 2), Exam consumer `1b161f3` dùng cờ `eligible` thay `status==APPROVED`. Scope: [parallel-participants-organization-scope.md](parallel-participants-organization-scope.md). Gateway route các prefix `/question-banks`, `/public-questions`, `/public-contributions`, `/organization-questions`, `/organization-contributions` (JWT bắt buộc).

## API mới/đổi (mọi route cần Bearer JWT; actor từ JWT/QB service lane, không từ client)
| Method path | Quyền | Ghi chú |
|---|---|---|
| GET /public-questions?bankId&sourceOwnerUserId&questionType&difficulty&text&mine&publicationStatus&page&size | Teacher/Student đã đăng nhập | mặc định chỉ PUBLISHED; owner dùng `mine=true` xem cả publication của mình; `text` (≤200 ký tự, LIKE escaped) tìm trong content; size ≤100; không key |
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


## BACKEND CONTRACT LOCK — Bank UI additions

MAIN đã review `7881a883333c009849e89307b0b2e17543dcd7de` và privacy fix
`59f2ec5afeb24f853d5a0a1fc9bdfa14dc548aaf`; không đổi lifecycle, eligibility,
authorization hay frozen snapshot của contract hiện hành.

### Organization submission note

`POST /questions/{questionId}/organization-contributions` nhận
`{bankId, sourceQuestionVersion, submissionNote?}`. `submissionNote` tối đa 5000
ký tự, trim, blank thành null. List/detail `OrganizationContributionResponse`
trả `submissionNote` riêng với `reviewNote`; không dùng ghi chú gửi làm quyết định
review. Quyền SUBMIT/READ/REVIEW giữ nguyên. Flyway V6 chỉ thêm nullable
`organization_contributions.submission_note VARCHAR(5000)`, dữ liệu cũ vẫn hợp lệ.

### Own Public publications

`GET /public-questions` thêm `mine` (default false) và `publicationStatus`
(`PUBLISHED|ARCHIVED`, optional). Default vẫn PUBLISHED candidate-safe.
`mine=true` bind owner từ JWT: không có status trả cả PUBLISHED/ARCHIVED của
actor; có status lọc đúng status. `mine=false&publicationStatus=ARCHIVED` trả
403; `mine=true&sourceOwnerUserId=<khác actor>` trả 403. Filter tác giả công khai
vẫn chỉ xem PUBLISHED. Unpublish không xóa publication, fork hay Exam snapshot.

### Lineage nodes

`GET /questions/{copiedQuestionId}/lineage` giữ flat fields cũ và thêm `nodes[]`:
`{relation, id, version, bankId, bankType, status, name, available, frozen}`.
`relation` = `SOURCE|PUBLICATION|COPY`; `name` là tên bank, không phải tên tác giả,
email, nội dung Question hay đáp án. Status/name/bankId có thể null khi nguồn ẩn.

SOURCE dùng frozen source version/status từ bản copy đã được phép tạo. Đọc
Public/Organization publication không cấp quyền đọc tên Personal Bank nguồn:
SOURCE.name chỉ có khi viewer đọc được source bank, nếu không trả null dù frozen
snapshot vẫn available. PUBLICATION kiểm quyền hiện tại: Public ARCHIVED đối với
non-owner hoặc Organization mất BANK_READ/provider unavailable trả
available=false, status/name=null. COPY dùng Question độc lập hiện tại và quyền
của viewer. Không dùng live source để cập nhật copy, không có answer key/email
trong lineage DTO. Câu không có lineage xử lý trạng thái trống/404, không suy diễn
provenance chưa tồn tại.
