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


## BACKEND CONTRACT LOCK — Public posts, Question Sets và discovery (2026-10-08)

MAIN duyệt chuỗi `0be36e0`, `8b293a5`, `f180bb9`, `0e8d08d`.
Mỗi bài đăng là `SINGLE_QUESTION` hoặc `QUESTION_SET`, không phải mặc định
một card cho mỗi câu. Public publish trực tiếp; Organization review không đổi.
Teacher không có system authority từ persona.

### Personal Question Set

- `GET/POST /question-banks/{bankId}/question-sets`: owner Personal Bank.
- `GET /question-sets/{id}` và `/question-sets/{id}/versions/{version}`: owner.
- `PUT /question-sets/{id}`: `{title,description?,items:[{questionId,questionVersion}],expectedVersion}`.
  Create dùng cùng body không cần expectedVersion; 1–100 item, không trùng,
  chỉ Personal ACTIVE của owner ở version hiện tại. Title tối đa 200, description 2000.
- `DELETE /question-sets/{id}`: archive.
- `POST /question-sets/{id}/publish-public {version}`: publish snapshot hiện tại,
  idempotent cùng version đã publish. Mọi frozen item phải ACTIVE; nếu DRAFT trả 409.
  Update/archive/publish yêu cầu author và ownership; Student không được ghi.
- Response: `{id,bankId,ownerUserId,title,description,version,status,updatedAt,items}`;
  status ACTIVE/ARCHIVED. Version cũ giữ title/description/items riêng.
- Fork tạo Question độc lập DRAFT. Muốn republish: activate các câu đã copy,
  PUT Set với expectedVersion để tạo snapshot ACTIVE mới, rồi publish version mới.
  Không tự activate, không sửa snapshot cũ. Set legacy chưa publish chứa DRAFT
  cần owner cập nhật qua PUT; publication đã tồn tại giữ idempotency.

### Public API

- `GET /public-questions`: giữ filter cũ; thêm subjectId,categoryId,postType,
  ranking. Subject/category match bất kỳ item, một post chỉ xuất hiện một lần.
- ranking: NEWEST, COPY_DAY, COPY_MONTH, COPY_YEAR,
  RATING_DAY, RATING_MONTH, RATING_YEAR. Hai sort COPY/RATING độc lập.
- `GET /public-questions/{id}`: safe detail, không tự tăng views.
- `POST /public-questions/{id}/fork`: SINGLE legacy; body `{sourceQuestionVersion,targetBankId?}`.
- `POST /public-questions/{id}/fork-items/{itemId}`: cùng body, fork một item.
- `POST /public-questions/{id}/fork-set {targetBankId?}`: fork cả bộ, Teacher only.
- `PUT /public-questions/{id}/rating {rating}`: decimal 0–10 (không ép số nguyên),
  Teacher/Student đăng nhập; một rating mỗi actor/post, sửa được; owner không tự rate.
  Response `{publicationId,averageRating,ratingCount,myRating}`.
- `POST /public-questions/{id}/view`: body rỗng; chỉ khi thực sự mở detail.
  Không ghi ở list/prefetch. Một actor/post/ngày UTC; owner không tăng lượt.
  Response `{publicationId,views24h,views30d,views365d,viewsTotal}`.
- `GET /public-questions/taxonomy?kind=SUBJECT|CATEGORY&text&subjectId&page&size`:
  Page các taxonomy thực có trong PUBLISHED snapshots; không hardcode chủ đề.
  SUBJECT `{id,name}`; CATEGORY `{id,name,subjectId,subjectName,parentCategoryId,path}`.
  Text literal không phân biệt hoa thường, tối đa 100 ký tự; size tối đa 100.

### DTO và statistics

PublicResponse giữ field cũ; thêm postType,title,description,postVersion,itemCount,
items,subjects,categories,copies24h,copies30d,copies365d,copiesTotal,
averageRating,ratingCount,myRating,views24h,views30d,views365d,viewsTotal.
Item `{id,position,sourceQuestionId,sourceQuestionVersion,question}` là safe frozen DTO.
Subject/category giữ ID/name/path snapshot. Không email, private source bank name hay answer key.

Rolling windows: 24 giờ/30 ngày/365 ngày UTC. Copy đếm distinct non-owner actor/post;
fork một item hay cả bộ thuộc cùng post. Views total là tổng sự kiện actor/ngày hợp lệ.
Rating ranking dùng `(sum+25)/(n+5)` trong window, không có rating xếp cuối;
tie ratingCount DESC, publishedAt DESC, id ASC. Đây chỉ là thứ hạng;
UI hiển thị raw averageRating /10 và ratingCount, không dùng adjusted score làm điểm.
Edit rating giữ createdAt, không tạo lượt đánh giá mới. Không có rating: UI ghi
“Chưa có đánh giá”. Hiển thị rõ viewsTotal và copiesTotal; không giả số lịch sử.

### Exam, lỗi và migration

`POST /questions/batch?eligible=true` nhận Public item ID; legacy SINGLE publication ID
vẫn hợp lệ. QUESTION_SET parent ID không phải Question resource cho Exam picker.
Trusted internal snapshot lane giữ grading key; candidate lane không có key.
Fork/source/public/Exam snapshots không tự đổi khi source sửa.
401 token; 403 quyền; 404 tài nguyên không được đọc; 400 input không hợp lệ;
409 stale Set version hoặc snapshot không eligible. Không silently overwrite.
V7 tạo Set/version/items/copy events; V8 ratings/views/frozen taxonomy, nâng cấp dữ liệu cũ.
Worker xác nhận 60 module tests và Flyway fresh/upgrade PostgreSQL PASS;
đây không thay thế unified runtime/E2E sau tích hợp UI.

## Backend Contract Lock — Saved / picker / explanation (2026-10-09)

Provider reviewed `fbb5094`, integrated vào MAIN. Frontend/runtime validation còn chờ.

- `PUT /public-questions/{id}/bookmark` → 204 idempotent; Teacher/Student JWT actor,
  chỉ PUBLISHED, missing/ARCHIVED → 404, actor không phù hợp → 403.
- `DELETE /public-questions/{id}/bookmark` → 204 idempotent; chỉ xóa bookmark của
  actor, kể cả bài đã archived/missing. Không tăng view/copy/rating.
- `GET /public-questions?saved=true` giữ toàn bộ filter/ranking/pagination, chỉ
  PUBLISHED đã lưu của actor; saved + ARCHIVED → 400. PublicResponse thêm `isSaved`.
- `GET /question-banks/{bankId}/questions?status=ACTIVE&text=&page=&size=`:
  quyền bank hiện hữu, literal content search case-insensitive, text ≤200, size≤100,
  stable pagination. Có cả `GET /questions?scope=PERSONAL&status=ACTIVE&text=...`.
- `GET /question-banks/{bankId}/question-sets?text=&status=&page=&size=`:
  owner-only, literal title search, status ACTIVE/ARCHIVED, size≤100, stable paging.
  Pool không giới hạn 100 câu; Set tối đa 100 item như contract hiện hữu.
- Create/Update Question thêm optional `explanation` ≤10000. Update thiếu/null
  giữ giá trị, empty string xóa. Trusted includeAnswers DTO giữ đúng QuestionVersion;
  SafeQuestionResponse/Public response không có explanation/answer key.
- Migration V9: nullable questions.explanation và bookmark PK(actor_user_id,publication_id)
  trong chính database QB; không cross-service DB. Fork/publication giữ frozen lineage.

Frontend không được coi field explanation là dữ liệu Public/candidate-safe; author
editor dùng trusted author API, Student result chỉ dùng Grading policy-filtered DTO.
