# Question Bank bank-aware lifecycle contract

Tài liệu service-local này mô tả implementation hiện tại để MAIN, Exam Taking và
Frontend tích hợp sau review. Shared contract do MAIN sở hữu vẫn là nguồn quyết định.

## Lifecycle và publication

- `Question.status` hiện hành: `DRAFT`, `ACTIVE`, `ARCHIVED`.
- `PENDING_REVIEW`, `APPROVED`, `REJECTED` chỉ còn trong Java enum để đọc JSON
  snapshot lịch sử; constraint V4 không cho ghi các giá trị này vào Question hiện tại.
- Owner tạo Personal Question ở `DRAFT`, gọi `POST /questions/{id}/activate` với
  `questionVersion`, rồi dùng trực tiếp. Sửa Question `ACTIVE` tạo version mới ở
  `DRAFT`; owner chủ động activate lại. Không có review gate cho Personal.
- `POST /questions/{id}/submit-review` và `POST /questions/{id}/review` trả `410`.
  `GET /questions/{id}/reviews` vẫn đọc audit lịch sử.
- Public dùng `POST /questions/{id}/publish-public`: chỉ owner Teacher của current
  Personal `ACTIVE` version được publish. API tạo immutable `PublicQuestion` ngay,
  trả `publicationStatus=PUBLISHED`; source sửa/archive không đổi snapshot.
- Các route Public contribution/review Phase 2 cũ được giữ để đọc lịch sử; create và
  review trả `410`. Legacy `PUBLIC_VERIFIED` publication vẫn được browse và được biểu
  diễn ra ngoài là `PUBLISHED`.
- Organization contribution có lifecycle riêng `PENDING_REVIEW -> APPROVED|REJECTED`.
  Approve tạo immutable `OrganizationQuestion`; source Personal và version history
  không bị sửa. Reviewer không được review contribution của chính mình.

## Authorization

- Personal mutation/read/answer key gắn với owner; `TEACHER` hoặc legacy `ADMIN`
  không có quyền trên Question của người khác.
- System create/mutate yêu cầu signed `systemPrivilege=ADMIN`; persona `TEACHER`
  không bao giờ thay thế quyền này.
- Organization gọi online `POST /organizations/internal/authorize` bằng dedicated
  `ORG_AUTHZ_QB_TOKEN` cho `BANK_READ`, `BANK_USE`, `BANK_SUBMIT`, `BANK_REVIEW`,
  `BANK_MANAGE`. Token trống, timeout, provider lỗi, inactive organization/membership
  hoặc deny đều fail closed. Không cache allow và không dùng legacy `bank_authorities`.
- Danh sách Organization bank theo membership chờ provider handoff cho bounded
  memberships discovery; direct bank/contribution/publication routes đã kiểm online.

## API cho frontend

| Route | Input chính | Kết quả/quyền |
| --- | --- | --- |
| `POST /questions` | `CreateRequest` | Personal `DRAFT` |
| `PUT /questions/{id}` | patch + `questionVersion` | version mới `DRAFT` |
| `POST /questions/{id}/activate` | `{questionVersion}` | current `ACTIVE` |
| `DELETE /questions/{id}?questionVersion=` | optimistic version | `ARCHIVED` |
| `GET /questions/{id}/versions[/version]` | `includeAnswers=false` | immutable history |
| `POST /questions/{id}/publish-public` | `{sourceQuestionVersion}` | `PublicResponse`, published ngay |
| `GET /public-questions[/{id}]` | owner/type/difficulty/page filters | candidate-safe immutable snapshot |
| `POST /public-questions/{id}/fork` | source version + optional Personal target | independent `DRAFT` + lineage |
| `POST /questions/{id}/organization-contributions` | `{bankId,sourceQuestionVersion}` | `PENDING_REVIEW`, cần `BANK_SUBMIT` |
| `POST /organization-contributions/{id}/review` | APPROVED/REJECTED + note | `BANK_REVIEW`, cấm self-review |
| `GET /organization-questions` | bank/type/difficulty/page | approved snapshots, cần `BANK_USE` |
| `POST /organization-questions/{id}/fork` | source version + target | Personal `DRAFT` + lineage |

`SafeQuestionResponse` và mọi browse/list/detail candidate-safe không chứa
`option.correct`, `expectedAnswer` hoặc `sourceExcerpt`. Teacher lấy key từ Question
Personal mà mình sở hữu; publication key chỉ qua authenticated internal batch lane.

## Exam eligible contract

`GET /questions/random` nhận `categoryId`, `difficulty`, `limit` (1..200), optional
`questionType`, optional `bankId`, optional `status=ACTIVE`; trả `UUID[]`.

`POST /questions/batch?eligible=true&includeAnswers=false` nhận tối đa 1000 ID thô,
200 ID riêng biệt và giữ thứ tự đầu tiên. Internal caller phải gửi exact
`X-Service-Token` và `X-Question-Owner-Id`; `eligible=false` bị từ chối.

Eligibility do provider tính:

- Personal: current `ACTIVE`, đúng owner/access;
- System: current `ACTIVE`;
- Public: immutable published snapshot;
- Organization: immutable approved publication và current `BANK_USE`.

DTO batch thêm `bankId`, `bankType`, `publicationStatus`, `eligible=true`, giữ
`id`, `questionVersion`, content/type/options. `status` giữ lifecycle thật (`ACTIVE`),
không giả `APPROVED`. `includeAnswers=true` chỉ cho owner phù hợp hoặc internal
server credential; candidate response không có grading key. Exam consumer phải dùng
marker `eligible`, không hard-code `status=APPROVED`, rồi freeze snapshot/version/key.

## Migration V4

V4 additive, không sửa V1/V2/V3. Migration ghi `question_lifecycle_migrations`
trước khi map current status: `APPROVED -> ACTIVE`, `PENDING_REVIEW|REJECTED -> DRAFT`,
giữ `DRAFT|ARCHIVED`. Snapshot JSON, review audit, Question/option/version IDs và
legacy Public rows giữ nguyên. V4 thêm Organization contribution/publication cùng
lineage `source_organization_question_id`; không tạo hoặc đọc bảng Organization DB.

## Graphify delta cho MAIN

- `Question -> QuestionVersion`: immutable full snapshot.
- `Question ACTIVE -> PublicQuestion PUBLISHED`: direct immutable publication.
- `Question ACTIVE -> OrganizationContribution -> OrganizationQuestion APPROVED`.
- `PublicQuestion|OrganizationQuestion -> QuestionLineage -> Personal Question fork`.
- `ExamEligibilityService` quyết định Personal/System/Public/Organization eligibility;
  Organization edges qua `OrganizationAuthorizationClient`, fail closed.

Graphify canonical do MAIN refresh sau merge. Không có frontend, AI, ingestion,
rating, moderation, Notification publisher hoặc Organization membership persistence
trong Question Bank.
